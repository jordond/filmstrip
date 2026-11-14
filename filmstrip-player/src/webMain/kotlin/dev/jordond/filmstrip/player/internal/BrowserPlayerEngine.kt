package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngineId
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.browserCanComposite
import dev.jordond.filmstrip.webcodecs.internal.preview.browserCanMonitorAudio
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

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
 * @param readSyncSample Reads the sync sample a relaxed seek lands on, at or before a position. A test swaps in one it
 *   can hold open.
 * @param drawShuttleFrame Draws the frame a shuttle shows at a position. A test swaps in one it can read back.
 */
internal class BrowserPlayerEngine(
  parent: CoroutineScope,
  planner: BrowserPreviewPlanner,
  config: PlayerConfig,
  startAudio: suspend (graph: BrowserAudioPreview?, from: Duration) -> Unit = { graph, from ->
    graph?.start(from)
  },
  private val drawFirstFrame: suspend (preview: BrowserPreview, at: Duration) -> Boolean = { preview, at ->
    preview.frameAt(at) != null
  },
  readSyncSample: suspend (preview: BrowserPreview, position: Duration) -> Duration = { preview, position ->
    preview.syncSampleAt(position)
  },
  private val drawShuttleFrame: suspend (preview: BrowserPreview, at: Duration) -> PreviewFrame? = { preview, at ->
    preview.frameAt(at)
  },
) : PlannedPlayerEngine<BrowserPreviewPlan>(parent, config.qualityPolicy, config.exportSpec, planner) {
  private var preview: BrowserPreview? = null
  private var audio: BrowserAudioPreview? = null
  private var clock: PreviewClock = MonotonicClock()

  private var volume = 1f
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  private val guard = WrapGuard()
  private var firstFrameOwed = false
  private var shuttling = false

  // The frame the shuttle drew last and the slot it sits on, which the surface reads back instead of drawing again.
  private var shuttleFrame: Pair<Duration, PreviewFrame>? = null

  // Counts the loads applied, so a frame drawn before one is never kept after it.
  private var appliedLoads = 0

  private val onVisibilityChanged: () -> Unit = { if (document.hidden) onPageHidden() }

  private val transport =
    BrowserTransport(
      scope = scope,
      guard = guard,
      startAudio = startAudio,
      preview = { preview },
      audio = { audio },
      clock = { clock },
      resolved = { plan?.resolved },
      position = ::readPosition,
      loopRange = { loopRange },
      playbackRange = { playbackRange },
      wrapTo = ::seekToWrap,
      setStatus = ::setStatus,
      setPlayWhenReady = ::setPlayWhenReady,
      reportExternalPlayWhenReady = ::reportExternalPlayWhenReady,
      emitEvent = ::emitEvent,
      fail = ::fail,
    )

  private val seeks =
    BrowserSeeks(
      scope = scope,
      guard = guard,
      transport = transport,
      preview = { preview },
      resolved = { plan?.resolved },
      clock = { clock },
      loopRange = { loopRange },
      isWrapSeek = { isWrapSeek },
      readSyncSample = readSyncSample,
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
   * The same reading the position ticker takes while playback runs, apart from a seek in flight, where the ticker holds
   * the seek's target.
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
  val transportStarted: Boolean get() = transport.started

  override val id: PlayerEngineId = PlayerEngineId("filmstrip.webcodecs")

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
          add(PlayerFeature.Shuttle)
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
      held = ::heldShuttleFrame,
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
   * Every seek is put to the guard, and a landing that waited through a rebuild, a failure or a release writes nothing,
   * since the chase has already answered for it.
   */
  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    seeks.seek(position, accuracy, onComplete)
  }

  override val shuttleRange: TimeRange? get() = playbackRange

  // The surface pulls each frame through the readback and every pull composites again, so the report the base sends
  // after a held redraw is the whole of one here.
  override val redrawsHeldFrames: Boolean get() = true

  // Drawn here and not by the surface, so the pump is paced by the frames the page can draw. The surface asks for
  // each one on its own tick and a frame slower than that tick would be cancelled before it showed.
  override fun presentFrame(
    position: Duration,
    onPresented: (landed: Duration) -> Unit,
  ) {
    val drawnFor = appliedLoads
    seeks.present(position, drawShuttleFrame) { landed, frame ->
      shuttleFrame = frame?.takeIf { drawnFor == appliedLoads && shuttling }?.let { landed to it }
      onPresented(landed)
    }
  }

  override fun onShuttleChanged(shuttling: Boolean) {
    this.shuttling = shuttling
    shuttleFrame = null
    // The look-ahead holds frames for a playhead a shuttle leaves behind.
    if (shuttling) preview?.flush()
    audio?.setVolume(audibleVolume())
  }

  private fun heldShuttleFrame(position: Duration): PreviewFrame? {
    val (landing, frame) = shuttleFrame ?: return null
    return frame.takeIf { plan?.resolved?.seekLandingOf(position) == landing }
  }

  private fun audibleVolume(): Float = if (shuttling) 0f else volume

  /**
   * Suspends until every seek landing and transport start this engine launched has finished.
   *
   * The loops a start leaves running are not waited on, so the engine is only quiet once this returns after a pause.
   */
  internal suspend fun awaitQuiet() {
    transport.awaitQuiet()
  }

  override fun onPlay() {
    transport.play()
  }

  override fun onPause() {
    transport.pause()
  }

  override fun onRelease() {
    document.removeEventListener(VISIBILITY_CHANGE, onVisibilityChanged)
    pending.supersede()
    seeks.invalidate()
    guard.reset()
    transport.pause()
    audio = null
    preview?.release()
    preview = null
  }

  override fun onStepFrames(frames: Int) {
    if (frames == 0) return
    val landing = plan?.resolved?.frameStepLanding(reportedPosition, frames) ?: return
    seekTo(landing, SeekAccuracy.Exact)
  }

  override fun setVolume(volume: Float) {
    this.volume = volume.coerceIn(0f, 1f)
    audio?.setVolume(audibleVolume())
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
    transport.stopForPlatform()
  }

  /**
   * Reacts to the page leaving the screen.
   *
   * Pausing a hidden page is filmstrip's choice rather than the platform's. A hidden tab playing
   * audio keeps its frame callbacks and a silent one loses them, so a silent preview left running
   * here would starve rather than play.
   */
  internal fun onPageHidden() {
    transport.stopForPlatform()
  }

  override suspend fun apply(
    change: CompositionDiff,
    next: BrowserPreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    val current = reportedPosition
    guard.reset()
    shuttleFrame = null
    appliedLoads++
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
      // A swap keeps the seek in flight, whose landing would otherwise write over this one. The guard forgets it too,
      // since a stale landing never reports to it.
      if (swapped) {
        seeks.invalidate()
        guard.reset()
      }
      clock.moveTo(at)
      movePlayhead(at)
    }
    if (swapped) emitFrameRedrawn()

    // A rebuild this one replaced mid draw leaves its frame owed, and its stopped transport, to whichever edit is next.
    preview?.takeIf { firstFrameOwed }?.let { firstFrameRefusal(it) }?.let { return it }
    setStatus(PlaybackStatus.Ready)
    when {
      request.playWhenReady -> {
        play()
      }
      // A swap moved to its start leaves the graph sounding the old playhead, so the transport starts again from the
      // new one.
      transport.wantsPlayback && swapped && landing != null -> {
        transport.launchStart { landing }
      }
      transport.wantsPlayback && !transport.started && !transport.startPending -> {
        transport.launchStart { readPosition() }
      }
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
    transport.stop()
    setStatus(PlaybackStatus.Preparing)
    preview?.release()

    val built = next.resolved.toBrowserPreview(next.edit)
    preview = built
    firstFrameOwed = true
    audio =
      built.audio()?.also { graph ->
        graph.setVolume(audibleVolume())
        // Closing the graph this replaces reports a change too, which says nothing about this one.
        graph.onStateChanged { scope.launch { if (audio === graph) transport.onAudioStateChanged() } }
      }
    clock = audio?.let { graph -> AudioClock({ graph.position }, { graph.isRunning }) } ?: MonotonicClock()
    transport.clearEnd()
    // Leaving Ready drops the seek in flight from the chase, so its landing is stale. A parameters-only swap stays
    // Ready and the chase still waits on that landing.
    seeks.invalidate()
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

    if (!drawn) return FilmstripError.InvalidComposition(NO_FIRST_FRAME).toPlaybackError()
    firstFrameOwed = false
    emitEvent(PlaybackEvent.FirstFrameRendered)
    return null
  }

  override fun fail(error: PlaybackError) {
    seeks.invalidate()
    guard.reset()
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
  }

  private companion object {
    const val VISIBILITY_CHANGE = "visibilitychange"
  }
}

/**
 * What a load is refused with when its first frame cannot be drawn, because the decoder handed back no frame there.
 */
internal const val NO_FIRST_FRAME = "The browser could not decode a frame of this composition."
