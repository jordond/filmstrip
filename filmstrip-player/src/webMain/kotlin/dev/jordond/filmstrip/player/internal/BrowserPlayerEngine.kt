package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.browserCanComposite
import dev.jordond.filmstrip.webcodecs.internal.preview.browserCanMonitorAudio
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The browser preview engine, on WebCodecs and mediabunny.
 *
 * Every frame a listener can see is drawn by the compositor an export of the same edit encodes
 * from, through the same lowering, so preview and export are one pipeline. Nothing is presented:
 * the surface pulls frames through [readback] and draws them itself.
 *
 * Three loops run while playback does, and they are separate because they fail differently. The
 * clock counts, on the audio hardware where there is audio and on wall time where there is not. The
 * transport loop watches for the end of the composition, a loop point and a browser that will not
 * start the audio graph. The render pump decodes ahead, driven from the page's frame callback,
 * which a background tab throttles and a background tab playing audio does not.
 *
 * Not thread safe, and neither is [BasePlayerEngine]. Everything is confined to [scope].
 *
 * @param parent The scope this engine's own is a child of, on the dispatcher everything is
 *   confined to.
 * @param planner Lowers an edit the way an export of it would be lowered.
 * @param config How the player was built.
 * @param startAudio Starts the audio graph, when there is one, from a position. Every transport start runs it before
 *   the clock starts, and a test swaps in one it can hold open.
 * @param drawFirstFrame Draws the frame a new graph owes at a position, answering whether it drew one. A test swaps in
 *   one it can hold open.
 */
internal class BrowserPlayerEngine(
  parent: CoroutineScope,
  planner: BrowserPreviewPlanner,
  config: PlayerConfig,
  private val startAudio: suspend (graph: BrowserAudioPreview?, from: Duration) -> Unit = { graph, from ->
    graph?.start(from)
  },
  private val drawFirstFrame: suspend (preview: BrowserPreview, at: Duration) -> Boolean = { preview, at ->
    preview.frameAt(at) != null
  },
) : PlannedPlayerEngine<BrowserPreviewPlan>(parent, config.qualityPolicy, config.exportSpec, planner) {
  private var preview: BrowserPreview? = null
  private var audio: BrowserAudioPreview? = null
  private var clock: PreviewClock = MonotonicClock()

  private var volume = 1f
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  private val guard = WrapGuard()
  private var seekSerial = 0
  private val fenced = mutableListOf<Job>()
  private var starting: Job? = null
  private var wantsPlayback = false
  private var reachedEnd = false
  private var startedAt: Duration? = null
  private var firstFrameOwed = false

  private val onVisibilityChanged: () -> Unit = { if (document.hidden) onPageHidden() }

  private val loops =
    BrowserPlaybackLoops(
      scope = scope,
      isPlaying = { wantsPlayback },
      onTick = ::onTransportTick,
      onFrame = {
        val target = preview
        target?.fillAhead(readPosition())
        target != null
      },
    )

  /**
   * How many times the browser graph has been built or rebuilt.
   *
   * A structural edit costs a fresh compositor and a fresh decoder. A parameters-only edit and an
   * equal one cost nothing here, which is what this counts.
   */
  var platformLoads: Int = 0
    private set

  /**
   * Where the preview clock has the playhead, which is the time the next frame the surface asks for is drawn at.
   *
   * The same reading the position ticker takes while playback runs.
   */
  val clockPosition: Duration get() = readPosition()

  /**
   * Whether the preview clock is advancing the playhead.
   *
   * The audio branch reads false while the browser holds the graph suspended, even after the transport started it.
   */
  val clockRunning: Boolean get() = clock.isRunning

  /**
   * Whether a transport start has reached the clock and nothing has stopped it since.
   *
   * True while the browser holds the audio graph suspended, which is what separates it from [clockRunning].
   */
  val transportStarted: Boolean get() = startedAt != null

  override val id: String = "filmstrip.webcodecs"

  // Discovered, not assumed. Readback is the whole display path here, so a page that cannot give
  // out a WebGL2 context has none of the drawing features rather than some of them. HdrPreview and
  // PlaybackSpeed stay unclaimed: the canvas composites in standard range, and nothing here varies
  // the rate the clock counts at yet.
  override val features: PlayerFeatures =
    PlayerFeatures(
      buildSet {
        if (browserCanComposite()) {
          add(PlayerFeature.FrameReadback)
          add(PlayerFeature.LiveParameterRedraw)
          add(PlayerFeature.FrameStepping)
        }
        // Unconditional wherever the API is there. GainNode.gain is legal on a suspended context
        // and applies when it resumes, so monitor volume is never actually unavailable, and a
        // browser refusing to start the graph is a transport occasion rather than a missing
        // capability.
        if (browserCanMonitorAudio()) add(PlayerFeature.AudioMonitoring)
      },
    )

  override val readback: PreviewFrameReadback =
    BrowserFrameReadback(
      scope = scope,
      preview = { preview },
      renderScale = { plan?.info?.renderScale ?: 1f },
    )

  // A page has no player object to attach to. The compositor renders into an OffscreenCanvas that
  // is never added to the document, and the surface draws the read-back bytes, so there is nothing
  // a host could usefully be handed here.
  override val nativePlayer: Any? = null

  init {
    document.addEventListener(VISIBILITY_CHANGE, onVisibilityChanged)
  }

  // A graph that still owes its first frame is not presentable yet, so an equal edit builds it again.
  override val isGraphStanding: Boolean get() = preview != null && !firstFrameOwed

  override fun readPosition(): Duration {
    val duration = plan?.resolved?.duration ?: return Duration.ZERO
    return clock.position.coerceAtLeast(Duration.ZERO).coerceAtMost(duration)
  }

  override fun isSeekReady(): Boolean = preview != null

  /**
   * Moves the playhead, which on this backend is the whole of a seek.
   *
   * Nothing is presented, so there is no decoder to wait on: the next frame the surface asks for is
   * drawn from wherever the playhead now is. The playhead lands on an output slot, the one the
   * target rounds to for [SeekAccuracy.Exact]. [SeekAccuracy.Nearest] lands on the slot of the sync
   * sample at or before the target, which is the same sample a decoder would have had to start from
   * anyway.
   *
   * Every seek is put to the guard. A wrap that lands after the loop range changed moves on to the first slot of the
   * range standing then, and reports that as its landing. A landing that waited through a rebuild, a failure or a
   * release writes nothing, since the chase has already answered for it.
   */
  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val target = preview
    val resolved = plan?.resolved
    if (target == null || resolved == null) {
      onComplete(position)
      return
    }

    guard.seekIssued(wrap = isWrapSeek)
    val seek = ++seekSerial
    fence(
      scope.launch {
        val requested = position.coerceAtLeast(Duration.ZERO).coerceAtMost(resolved.duration)
        val sent =
          when (accuracy) {
            SeekAccuracy.Exact -> resolved.seekLandingOf(requested)
            SeekAccuracy.Nearest -> resolved.seekLandingOf(target.syncSampleAt(requested))
          }
        if (seek != seekSerial) return@launch
        val redirect = guard.seekLanded(loopRange?.let { loopWrapOf(it, resolved.duration, resolved.frameRate) })
        // A redirect is one more exact seek, which on this backend lands as soon as the clock moves.
        if (redirect != null) guard.seekLanded(null)
        val landing = redirect?.let { outputSlotTime(it, resolved.frameRate) } ?: sent

        reachedEnd = false
        // Everything decoded ahead of the old playhead is decoded for a position nobody is going to
        // ask for now, so it is closed here rather than left to fall out of the window one frame at a
        // time.
        target.flush()
        clock.moveTo(landing)
        if (wantsPlayback) launchStart { landing }
        onComplete(landing)
      },
    )
  }

  /**
   * Suspends until every seek landing and transport start this engine launched has finished.
   *
   * A relaxed landing waits on the preview's lock behind any readbacks queued first, then writes the playhead and
   * reports its completion, or finds itself stale and writes nothing. A transport start either starts the clock and
   * both loops, or is cancelled by whatever superseded it and does nothing more. Once this returns, all of them have
   * done one or the other. Work launched while this waits, the way a landing chains the seek queued behind it, is
   * waited on too.
   *
   * The transport and pump loops a start leaves running are not waited on, since they run for as long as playback
   * does. The engine is only quiet once this returns after a pause.
   */
  internal suspend fun awaitQuiet() {
    while (true) {
      fenced.removeAll { it.isCompleted }
      val next = fenced.firstOrNull() ?: return
      next.join()
    }
  }

  override fun onPlay() {
    if (preview == null) return
    reachedEnd = false
    wantsPlayback = true
    launchStart { readPosition() }
  }

  override fun onPause() {
    wantsPlayback = false
    stopTransport()
  }

  override fun onRelease() {
    document.removeEventListener(VISIBILITY_CHANGE, onVisibilityChanged)
    pending.supersede()
    wantsPlayback = false
    seekSerial++
    guard.reset()
    stopTransport()
    audio = null
    preview?.release()
    preview = null
  }

  override fun stepFrames(frames: Int) {
    if (frames == 0) return
    val landing = plan?.resolved?.frameStepLanding(readPosition(), frames) ?: return
    seekTo(landing, SeekAccuracy.Exact)
  }

  override fun setVolume(volume: Float) {
    this.volume = volume.coerceIn(0f, 1f)
    audio?.setVolume(this.volume)
  }

  override fun setLoopRange(range: TimeRange?) {
    if (range == loopRange) return
    loopRange = range
    guard.rangeChanged()
  }

  override fun setPlaybackRange(range: TimeRange?) {
    playbackRange = range
    range?.start?.takeIf { readPosition() < it }?.let { seekTo(it, SeekAccuracy.Exact) }
  }

  /**
   * Reacts to the browser refusing to start the audio graph without a fresh user gesture.
   *
   * The one occasion with no analogue on any other backend. The engine wanted to play, the platform
   * refused, and `playWhenReady` has to go back to false or the snapshot lies.
   */
  internal fun onAutoplayRefused() {
    stopForPlatform()
  }

  /**
   * Reacts to the page leaving the screen.
   *
   * Pausing a hidden page is filmstrip's choice rather than the platform's. A hidden tab playing
   * audio keeps its frame callbacks and a silent one loses them, so a silent preview left running
   * here would starve rather than play.
   */
  internal fun onPageHidden() {
    stopForPlatform()
  }

  // Stops a preview the platform took out of playing, and reports playWhenReady as false the way any other external
  // pause would be.
  private fun stopForPlatform() {
    if (!wantsPlayback) return
    wantsPlayback = false
    stopTransport()
    reportExternalPlayWhenReady(false)
  }

  override suspend fun apply(
    change: CompositionDiff,
    next: BrowserPreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    val current = readPosition()
    guard.reset()
    val standing = preview
    val swapped =
      change == CompositionDiff.ParametersOnly &&
        standing != null &&
        standing.updateParameters(next.resolved, next.edit)
    val built = if (swapped) null else rebuild(next)

    loaded = request.composition
    plan = next
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    // A swap leaves the clock running where it was. A rebuild starts a fresh one, which holds the old playhead.
    val landing =
      request.startAt?.let(next.resolved::seekLandingOf)
        ?: built?.let { next.resolved.heldPosition(current, loopRange, isPlayWanted) }
    landing?.let { at ->
      clock.moveTo(at)
      emitPosition(at)
    }

    // A rebuild this one replaced mid draw leaves its frame owed, and its stopped transport, to whichever edit is next.
    preview?.takeIf { firstFrameOwed }?.let { firstFrameRefusal(it) }?.let { return it }
    setStatus(PlaybackStatus.Ready)
    when {
      request.playWhenReady -> play()
      wantsPlayback && !transportStarted && starting?.isActive != true -> launchStart { readPosition() }
    }
    return null
  }

  /**
   * Builds a fresh preview over the lowered plan, dropping whatever was standing.
   *
   * @return The preview built.
   */
  private fun rebuild(next: BrowserPreviewPlan): BrowserPreview {
    platformLoads++
    // Nothing of the old transport runs on over the new graph, and a start in flight would go on to start its clock.
    // The transport stays stopped until the load has its first frame.
    stopTransport()
    setStatus(PlaybackStatus.Preparing)
    preview?.release()

    val built = next.resolved.toBrowserPreview(next.edit)
    preview = built
    firstFrameOwed = true
    audio =
      built.audio()?.also { graph ->
        graph.setVolume(volume)
        // Closing the graph this replaces reports a change too, which says nothing about this one.
        graph.onStateChanged { scope.launch { if (audio === graph) onAudioStateChanged() } }
      }
    clock = audio?.let { AudioClock(it) } ?: MonotonicClock()
    reachedEnd = false
    // Leaving Ready drops the seek in flight from the chase, so its landing is stale. A parameters-only swap stays
    // Ready and the chase still waits on that landing.
    seekSerial++
    return built
  }

  /**
   * Draws one frame of [target] before the load is called a success.
   *
   * A page that cannot give out a context, or a source the decoder will not open, otherwise reads
   * as a loaded composition that never shows anything. Refusing the load here turns a preview that
   * stays black into a failure a caller can put on screen.
   *
   * @return null once the frame is drawn, or why the load is refused.
   */
  private suspend fun firstFrameRefusal(target: BrowserPreview): PlaybackError? {
    val drawn =
      try {
        drawFirstFrame(target, readPosition())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (
        @Suppress("TooGenericExceptionCaught") broken: Throwable,
      ) {
        return PlaybackError.Underlying(
          PlaybackError.Underlying.NO_PLATFORM_CODE,
          broken.message ?: "The browser could not draw this composition.",
        )
      }

    if (!drawn) return PlaybackError.SourceUnreadable("The browser could not decode a frame of this composition.")
    firstFrameOwed = false
    emitEvent(PlaybackEvent.FirstFrameRendered)
    return null
  }

  private fun fence(job: Job) {
    fenced.removeAll { it.isCompleted }
    fenced += job
  }

  /**
   * Launches a transport start from wherever [from] reads when it runs, cancelling the start still in flight.
   *
   * Only the newest start is ever live. A pause, a stop, a seek landing or a rebuild cancels it too, so a superseded
   * start never schedules audio, moves the graph's anchor or starts a clock once something has replaced it.
   */
  private fun launchStart(from: () -> Duration) {
    starting?.cancel()
    val job = scope.launch { startTransport(from()) }
    starting = job
    fence(job)
  }

  private fun cancelStart() {
    starting?.cancel()
    starting = null
  }

  /**
   * Starts the audio graph, the clock and both loops from [from].
   */
  private suspend fun startTransport(from: Duration) {
    loops.stop()
    startAudio(audio, from)
    clock.start(from)
    startedAt = wallClock()
    loops.start()
  }

  private fun stopTransport() {
    cancelStart()
    clock.stop()
    audio?.stop()
    startedAt = null
    loops.stop()
    // A frame held across a pause is held for as long as the user leaves it there, and re-seating
    // the decoder on resume costs one seek.
    preview?.flush()
  }

  /**
   * Everything transport has to notice while it runs, on a clock nothing in the page can throttle.
   */
  private suspend fun onTransportTick() {
    if (!wantsPlayback) return
    val resolved = plan?.resolved ?: return
    val duration = resolved.duration
    val at = readPosition()

    if (refusedToStart()) {
      onAutoplayRefused()
      return
    }

    audio?.pump(at)

    // A reading past the wrap point is never an ending, even while a seek in flight holds the wrap back.
    val range = loopRange
    val rate = resolved.frameRate
    val wrap = range?.let { loopWrapOf(it, duration, rate) }
    if (range != null && wrap != null && at >= wrap.at) {
      if (guard.mayWrap(running = wantsPlayback)) {
        seekToWrap(outputSlotTime(wrap.firstSlot, rate))
        emitEvent(PlaybackEvent.RangeLooped(range))
      }
      return
    }

    val end = playbackRange?.endExclusive ?: duration
    if (at >= end && !reachedEnd) {
      reachedEnd = true
      wantsPlayback = false
      stopTransport()
      setStatus(PlaybackStatus.Ended)
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(at, duration))
    }
  }

  /**
   * Whether the audio graph has been asked to run and has not started within the grace period.
   *
   * The Web Audio spec parks `resume`'s promise rather than rejecting it when a page is not allowed
   * to start, so there is nothing to await and no rejection to catch. The context's own state is
   * the only signal, read after giving it long enough to have changed.
   */
  private fun refusedToStart(): Boolean {
    val graph = audio ?: return false
    if (graph.isRunning) return false
    val since = startedAt ?: return false
    return wallClock() - since >= AUTOPLAY_GRACE
  }

  private fun onAudioStateChanged() {
    if (wantsPlayback && audio?.isRunning == false) onAutoplayRefused()
  }

  override fun fail(error: PlaybackError) {
    seekSerial++
    guard.reset()
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
  }

  private fun wallClock(): Duration = performance.now().milliseconds

  private companion object {
    const val VISIBILITY_CHANGE = "visibilitychange"
  }
}

/**
 * How long the audio graph gets to start before the refusal is reported.
 *
 * Long enough that a context resuming across a task boundary is never called a refusal, short enough that a play button
 * does not sit stuck for a noticeable moment.
 */
internal val AUTOPLAY_GRACE: Duration = 250.milliseconds
