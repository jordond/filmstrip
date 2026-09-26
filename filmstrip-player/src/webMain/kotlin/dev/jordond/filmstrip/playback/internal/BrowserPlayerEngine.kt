package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserAudioPreview
import dev.jordond.filmstrip.webcodecs.internal.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.browserCanComposite
import dev.jordond.filmstrip.webcodecs.internal.browserCanMonitorAudio
import dev.jordond.filmstrip.webcodecs.internal.toBrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The browser preview engine, on WebCodecs and mediabunny.
 *
 * Every frame a listener can see is drawn by the compositor an export of the same edit encodes
 * from, through the same lowering, so preview and export are one pipeline rather than two that
 * agree. Nothing is presented: the surface pulls frames through [readback] and draws them itself.
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
 */
internal class BrowserPlayerEngine(
  parent: CoroutineScope,
  private val planner: BrowserPreviewPlanner,
  config: PlayerConfig,
) : BasePlayerEngine(parent) {
  private var loaded: EditComposition? = null
  private var plan: BrowserPreviewPlan? = null
  private var preview: BrowserPreview? = null
  private var audio: BrowserAudioPreview? = null
  private var clock: PreviewClock = MonotonicClock()

  private var policy: PreviewQualityPolicy = config.qualityPolicy
  private var volume = 1f
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  private val pending = PendingLoad()
  private val guard = WrapGuard()
  private var seekSerial = 0
  private var transport: Job? = null
  private var pump: Job? = null
  private var pumpHandle: Int? = null
  private var wantsPlayback = false
  private var reachedEnd = false
  private var startedAt: Duration? = null

  private val onVisibilityChanged: () -> Unit = { if (document.hidden) onPageHidden() }

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

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending.supersede()

    val change = diff(loaded, request.composition)
    if (change == CompositionDiff.Equal && preview != null) {
      // Nothing at the platform level, not even a status move. A listener must not see this load.
      loaded = request.composition
      callback.onResult(SetCompositionResult.Success(plan?.resolved?.duration ?: Duration.ZERO))
      return Cancellable { }
    }

    val job =
      scope.launch {
        when (val result = planner.plan(request.composition, policy)) {
          is BrowserPlanResult.Refused -> {
            fail(result.error)
          }
          is BrowserPlanResult.Ready -> {
            apply(change, result.plan, request)
          }
        }
      }

    return pending.track(job, callback)
  }

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
      if (wantsPlayback) startTransport(landing)
      onComplete(landing)
    }
  }

  override fun onPlay() {
    if (preview == null) return
    reachedEnd = false
    wantsPlayback = true
    scope.launch { startTransport(readPosition()) }
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

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    if (this.policy == policy) return
    this.policy = policy

    // The cap moves the rendered frame, which moves the output format, and the compositor cannot
    // swap that in place. The graph is rebuilt against the edit already loaded.
    val composition = loaded ?: return
    loaded = null
    setComposition(SetCompositionRequest(composition), { })
  }

  /**
   * Reacts to the browser refusing to start the audio graph without a fresh user gesture.
   *
   * The one occasion with no analogue on any other backend. The engine wanted to play, the platform
   * refused, and `playWhenReady` has to go back to false or the snapshot lies.
   */
  internal fun onAutoplayRefused() {
    if (!wantsPlayback) return
    wantsPlayback = false
    stopTransport()
    reportExternalPlayWhenReady(false)
  }

  /**
   * Reacts to the page leaving the screen.
   *
   * Pausing a hidden page is filmstrip's choice rather than the platform's. A hidden tab playing
   * audio keeps its frame callbacks and a silent one loses them, so a silent preview left running
   * here would starve rather than play.
   */
  internal fun onPageHidden() {
    if (!wantsPlayback) return
    wantsPlayback = false
    stopTransport()
    reportExternalPlayWhenReady(false)
  }

  /**
   * Applies one lowered plan, rebuilding only what the change actually costs.
   */
  private suspend fun apply(
    change: CompositionDiff,
    next: BrowserPreviewPlan,
    request: SetCompositionRequest,
  ) {
    guard.reset()
    val standing = preview
    val swapped =
      change == CompositionDiff.ParametersOnly &&
        standing != null &&
        standing.updateParameters(next.resolved, next.edit)
    if (!swapped) {
      rebuild(next)
    }

    loaded = request.composition
    plan = next
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    request.startAt?.let { clock.moveTo(next.resolved.seekLandingOf(it)) }

    if (!swapped && !confirmFirstFrame()) return
    setStatus(PlaybackStatus.Ready)
    pending.settle(SetCompositionResult.Success(next.resolved.duration))
    if (request.playWhenReady) play()
  }

  /**
   * Builds a fresh preview over the lowered plan, dropping whatever was standing.
   */
  private fun rebuild(next: BrowserPreviewPlan) {
    platformLoads++
    setStatus(PlaybackStatus.Preparing)
    preview?.release()

    val built = next.resolved.toBrowserPreview(next.edit)
    preview = built
    audio =
      built.audio()?.also { graph ->
        graph.setVolume(volume)
        graph.onStateChanged { scope.launch { onAudioStateChanged() } }
      }
    clock = audio?.let { AudioClock(it) } ?: MonotonicClock()
    reachedEnd = false
    // Leaving Ready drops the seek in flight from the chase, so its landing is stale. A parameters-only swap stays
    // Ready and the chase still waits on that landing.
    seekSerial++
  }

  /**
   * Draws one frame before calling the load a success.
   *
   * A page that cannot give out a context, or a source the decoder will not open, otherwise reads
   * as a loaded composition that never shows anything. Reporting it here turns a preview that stays
   * black into a failure a caller can put on screen.
   */
  private suspend fun confirmFirstFrame(): Boolean {
    val target = preview ?: return false
    val drawn =
      try {
        target.frameAt(readPosition()) != null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (
        @Suppress("TooGenericExceptionCaught") broken: Throwable,
      ) {
        fail(
          PlaybackError.Underlying(
            PlaybackError.Underlying.NO_PLATFORM_CODE,
            broken.message ?: "The browser could not draw this composition.",
          ),
        )
        return false
      }

    if (!drawn) {
      fail(PlaybackError.SourceUnreadable("The browser could not decode a frame of this composition."))
      return false
    }
    emitEvent(PlaybackEvent.FirstFrameRendered)
    return true
  }

  private suspend fun startTransport(from: Duration) {
    stopLoops()
    audio?.start(from)
    clock.start(from)
    startedAt = wallClock()
    startLoops()
  }

  private fun stopTransport() {
    clock.stop()
    audio?.stop()
    startedAt = null
    stopLoops()
    // A frame held across a pause is held for as long as the user leaves it there, and re-seating
    // the decoder on resume costs one seek.
    preview?.flush()
  }

  private fun startLoops() {
    transport =
      scope.launch {
        while (isActive && wantsPlayback) {
          delay(TRANSPORT_TICK)
          onTransportTick()
        }
      }
    pumpFrames()
  }

  private fun stopLoops() {
    transport?.cancel()
    transport = null
    // The frame callback and the decode it launches are two separate handles, and a preview about
    // to be released has to lose both. Cancelling only the callback leaves a decode running against
    // a preview nobody is going to draw from again.
    pumpHandle?.let { cancelAnimationFrame(it) }
    pumpHandle = null
    pump?.cancel()
    pump = null
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
      if (guard.wrapDue(at, running = wantsPlayback, wrap = wrap)) {
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

  /**
   * Decodes ahead of the playhead, once per painted frame.
   */
  private fun pumpFrames() {
    pumpHandle =
      requestAnimationFrame {
        pumpHandle = null
        pump =
          scope.launch {
            val target = preview
            if (target != null && wantsPlayback) {
              target.fillAhead(readPosition())
              pumpFrames()
            }
          }
      }
  }

  private fun fail(error: PlaybackError) {
    seekSerial++
    guard.reset()
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
    pending.settle(SetCompositionResult.Failure(error))
  }

  private fun wallClock(): Duration = performance.now().milliseconds

  private companion object {
    const val VISIBILITY_CHANGE = "visibilitychange"

    // Roughly display rate. The position ticker in BasePlayerEngine runs at the same cadence, so
    // nothing here is noticed later than the playhead a listener is already reading.
    val TRANSPORT_TICK = 16.milliseconds

    /**
     * How long the audio graph gets to start before the refusal is reported.
     *
     * Long enough that a context resuming across a task boundary is never called a refusal, short
     * enough that a play button does not sit stuck for a noticeable moment.
     */
    val AUTOPLAY_GRACE = 250.milliseconds
  }
}
