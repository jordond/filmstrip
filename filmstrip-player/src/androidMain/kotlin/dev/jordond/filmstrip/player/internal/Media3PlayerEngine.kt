package dev.jordond.filmstrip.player.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.media3.internal.preview.Media3Preview
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
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
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The media3 preview engine.
 *
 * Everything a frame goes through is what an export of the same edit would run: one
 * [ResolvedComposition] lowered by [toMedia3Preview] into one `Composition`, handed to a
 * `CompositionPlayer`. Preview and export are not two pipelines that agree, they are the same one.
 *
 * `CompositionPlayer` confines every call and every callback to one looper, so the engine owns a
 * thread of its own, builds the player there, posts every transport call onto it and hops each
 * callback back onto [scope], which is the dispatcher [BasePlayerEngine] and everything a listener
 * sees is confined to.
 *
 * @param parent The scope this engine's own is a child of, on the dispatcher everything a
 *   listener sees is confined to.
 * @param context The application context media3 decodes and renders against.
 * @param planner Lowers an edit the way an export of it would be lowered.
 * @param config How the player was built.
 * @param lower Builds the graph a plan plays through, throwing what the media3 lowering throws.
 */
@ExperimentalApi
internal class Media3PlayerEngine(
  parent: CoroutineScope,
  private val context: Context,
  private val planner: Media3PreviewPlanner,
  config: PlayerConfig,
  private val lower: (Media3PreviewPlan) -> Media3Preview = { it.resolved.toMedia3Preview(it.bounds) },
) : PlannedPlayerEngine<Media3PreviewPlan>(parent, config.qualityPolicy, config.exportSpec, planner) {
  private val thread = HandlerThread(THREAD_NAME).apply { start() }
  private val handler = Handler(thread.looper)

  private var preview: Media3Preview? = null
  private var volume = 1f

  private val scrubMode =
    Media3ScrubMode(handler, holdsOff = { seeks.playedSinceSeek }, onEnded = { heldPin.scrubEnded() }) {
      player?.setScrubbingModeEnabled(it)
    }

  private val guard = WrapGuard()

  private val redraws =
    Media3Redraws(
      scope = scope,
      handler = handler,
      playback = { player?.playbackLooper?.let(::Handler) },
      redraw = { player?.experimentalRedrawLastFrame() },
      paused = { player?.isPlaying != true },
      showsFrames = ::showsFrames,
      report = ::emitFrameRedrawn,
    )

  private val shutter = PreviewShutter(scope)

  private val loads = Media3GraphLoads(scope)

  private val status: Media3StatusTracker =
    Media3StatusTracker(
      shutter = shutter,
      hasPreview = { preview != null },
      wrapsAtEnd = { running -> loops.wrapsAtEnd(running) },
      position = ::readPosition,
      duration = { plan?.resolved?.duration ?: Duration.ZERO },
      stopPlayer = ::onPause,
      setStatus = ::setStatus,
      setStalled = ::setStalled,
      setPlayWhenReady = ::setPlayWhenReady,
      reportExternalPlayWhenReady = ::reportExternalPlayWhenReady,
      emitEvent = { event ->
        if (event is PlaybackEvent.Ended) {
          pausedFrame.onEnded()
          drawLastSlotIfShort()
        }
        emitEvent(event)
      },
    )

  internal val surfaces: Media3SurfaceManager =
    Media3SurfaceManager(
      scope = scope,
      handler = handler,
      player = { player },
      rendered = { plan?.resolved?.output?.size },
      graphUp = { status.graphUp },
      onDetached = ::onSurfaceDetached,
      drawnOnto = { status.surfaceRendered },
      represent = { surfaceLost -> scope.launch { seeks.represent(surfaceLost, ended = status.holdEnd()) } },
      onTargetMoved = ::surfaceLimitMoved,
    )

  private val pausedFrame: Media3PausedFrame =
    Media3PausedFrame(
      scope = scope,
      renderedUs = { status.renderedUs },
      isEnded = { status.isEnded },
      isPlayWanted = { isPlayWanted },
      landingOf = ::landingOf,
      duration = { plan?.resolved?.duration ?: Duration.ZERO },
      playhead = { seeks.position() },
      emitPosition = ::emitPosition,
    )

  private val heldPin =
    Media3HeldPin(
      player = { player },
      frameRate = { plan?.resolved?.frameRate },
      firstSlotOf = { preview?.firstSlotOf(it) ?: it },
      pinSlot = { preview?.pinSlot(it) },
      drawn = pausedFrame::drawn,
    )

  private val seeks: Media3SeekCoordinator =
    Media3SeekCoordinator(
      scope = scope,
      handler = handler,
      player = { player },
      playback = { player?.playbackLooper?.let(::Handler) },
      resolved = { plan?.resolved },
      guard = guard,
      platformPositionMs = { status.platformPositionMs },
      loopWrap = { loops.wrap() },
      showsFrames = ::showsFrames,
      scrubbing = { scrubMode.active },
      inScrubbingMode = { scrubMode.entered },
      enterScrubbingMode = scrubMode::enter,
      onIssued = {
        status.onSeekIssued()
        status.renderedUs = NO_FRAME
      },
      onRedirected = ::movePlayhead,
      firstSlotOf = { preview?.firstSlotOf(it) ?: it },
      pin = heldPin,
      resting = pausedFrame::resting,
      restingAt = { at -> if (isPlayWanted) at else landingOf(at) },
      playWanted = { isPlayWanted },
      onRepresented = ::onRepresented,
    )

  private val loops: Media3LoopRangeController =
    Media3LoopRangeController(
      loads = loads,
      handler = handler,
      player = { player },
      resolved = { plan?.resolved },
      guard = guard,
      playhead = ::readPosition,
      recordPosition = { status.platformPositionMs = it },
      seekTo = { seekTo(it, SeekAccuracy.Exact) },
      seekToWrap = ::seekToWrap,
      pause = ::pause,
      onLooped = { range -> emitEvent(PlaybackEvent.RangeLooped(range)) },
      onRead = pausedFrame::onClockRead,
    )

  private val host =
    Media3PlayerHost(
      context = context,
      scope = scope,
      thread = thread,
      loads = loads,
      status = status,
      seeks = seeks,
      loops = loops,
      surfaces = surfaces,
      onFrame = {
        redraws.frameDrawn()
        pausedFrame.onFrame()
      },
      onBuilt = {
        engines[it] = this
        scrubMode.carry()
      },
      onReleased = { engines.remove(it) },
    )

  private val player: CompositionPlayer? get() = host.player

  /**
   * How many times the media3 graph has been built or rebuilt.
   *
   * `CompositionPlayer.setComposition` reconfigures the whole pipeline on every call, so this
   * counts a decoder reinitialization. A parameters-only edit and an equal one cost nothing here.
   */
  var platformLoads: Int = 0
    private set

  /**
   * The accuracy the last seek really ran at.
   *
   * Not always the accuracy that was asked for. See [clampedAccuracy].
   */
  val lastSeekAccuracy: SeekAccuracy get() = seeks.lastAccuracy

  /**
   * The duration media3's own timeline reports, or null before it has one.
   */
  val platformDuration: Duration?
    get() = status.platformDurationMs.takeIf { it != C.TIME_UNSET }?.milliseconds

  /**
   * Where media3 itself says the playhead is.
   */
  val platformPosition: Duration get() = status.platformPositionMs.milliseconds

  /**
   * What waits out the work queued on the player's and media3's playback threads and the callbacks it hopped onto
   * [scope].
   *
   * See [Media3CallbackFence] for what it covers and what it cannot.
   */
  val fence: Media3CallbackFence =
    Media3CallbackFence(
      scope = scope,
      handler = handler,
      playbackLooper = { player?.playbackLooper },
      seekInFlight = { seeks.target != null },
    )

  override val id: PlayerEngineId = PlayerEngineId("filmstrip.media3")

  /**
   * What watches for the occasions media3 does not report. Reachable so a test can raise one.
   */
  val interruptions = Media3Interruptions(context) { scope.launch { status.onInterrupted() } }

  // PlaybackSpeed is absent from CompositionPlayer's own command set, so it is never claimed.
  // AudioMonitoring is asked of the player, since setVolume is only real where
  // the command is available. HdrPreview is asked of the display, which is what decides whether a
  // grade survives the last step.
  override val features: PlayerFeatures =
    host.start { built ->
      PlayerFeatures(
        buildSet {
          add(PlayerFeature.FrameReadback)
          add(PlayerFeature.FrameStepping)
          add(PlayerFeature.LiveParameterRedraw)
          add(PlayerFeature.Shuttle)
          if (built.isCommandAvailable(Player.COMMAND_SET_VOLUME)) add(PlayerFeature.AudioMonitoring)
          if (displayShowsHdr(context)) add(PlayerFeature.HdrPreview)
        },
      )
    }

  override val readback: PreviewFrameReadback
    field =
    Media3FrameReadback(
      scope = scope,
      context = context,
      preview = { preview },
      renderScale = { plan?.info?.renderScale ?: 1f },
      revision = { loaded?.effectsRevision() ?: 0L },
    )

  override val nativePlayer: Any? get() = player

  override val isGraphStanding: Boolean get() = preview != null

  override fun readPosition(): Duration = pausedFrame.position()

  override fun isSeekReady(): Boolean = preview != null && status.platformState != Player.STATE_IDLE

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    redraws.seekIssued()
    status.clearEnd()
    seeks.seek(position, accuracy, wrap = isWrapSeek, onComplete)
  }

  override fun onPlay() {
    status.clearEnd()
    val at = readPosition()
    // The pin has one owner, the player's thread, so this runs behind every play and pause asked for before it and
    // ahead of the pause asked for next. A seek asked for just before reaches the player two hops later and can be
    // handed over after this, where it finds the player playing and pins nothing. A pause on a later slot of a held
    // sample left only that slot drawn, so playing from it draws the sample again. Only a player that was paused has
    // drawn nothing but the slot it rests on, so a play asked for while one is already under way draws the sample as it
    // goes and is not sent back to its first slot.
    handler.post { seeks.resume(at) }
  }

  override fun onPause() {
    val at = readPosition()
    // Pinned on the player's thread, behind every play asked for before it, which can no longer clear it.
    handler.post { seeks.pause(at) }
    pausedFrame.onPaused()
  }

  override val shuttleRange: TimeRange? get() = loops.playbackRange

  override val redrawsHeldFrames: Boolean get() = true

  override fun redrawHeld() {
    redraws.requestHeld()
  }

  private fun showsFrames(): Boolean = status.surfaceRendered && preview?.let { it.videoSequences > 0 } == true

  override fun onScrubbingChanged(scrubbing: Boolean) {
    scrubMode.scrubbingChanged(scrubbing)
  }

  override fun onShuttleChanged(shuttling: Boolean) {
    scrubMode.shuttlingChanged(shuttling)
  }

  // media3's limit: the first frame it releases onto a buffer that changed size never shows, and only a redraw
  // reaches the buffer. The frame the seek back decoded is the one the cache replays.
  private fun onRepresented() {
    handler.post { player?.experimentalRedrawLastFrame() }
    emitFrameRedrawn()
  }

  /**
   * Draws the last slot of a preview that ended on an earlier one.
   *
   * A player too slow to keep up drops the frames that come late, and at the end of a play nothing comes after them to
   * draw, so the surface stays on a slot before the last while the playhead reads the end. The end is held through the
   * seek that draws it, so the preview still reads as ended and the end is not reported twice.
   */
  private fun drawLastSlotIfShort() {
    if (!showsFrames()) return
    val drawn = pausedFrame.drawn() ?: return
    val length = plan?.resolved?.duration ?: return
    if (drawn >= landingOf(length)) return
    scope.launch { seeks.represent(surfaceLost = false, ended = status.holdEnd()) }
  }

  private fun onSurfaceDetached() {
    redraws.surfaceDetached()
    status.onSurfaceDetached()
    seeks.framesStopped()
  }

  override val planBasis: Any? get() = true.takeIf { planner.sdrOnlySurface }

  // A surface that shows SDR only has a kept grade planned as a tone map, so a loaded edit is planned again as one is
  // attached or let go. An SDR edit plans the same either way and its graph is left standing.
  private fun surfaceLimitMoved() {
    planner.sdrOnlySurface = surfaces.previewTarget?.showsSdrOnly == true
    planBasisChanged()
  }

  /**
   * Follows the frame held over the surface while the graph is rebuilt. See [PreviewShutter].
   *
   * @return a handle that stops the reports.
   */
  fun observeStill(listener: (Bitmap?) -> Unit): Cancellable = shutter.addListener(listener)

  override fun onRelease() {
    pending.supersede()
    loops.stopMirror()
    seeks.cancel()
    interruptions.dispose()
    readback.dispose()
    shutter.dispose()
    preview = null
    handler.post {
      surfaces.release()
      host.release()
    }
  }

  override fun onStepFrames(frames: Int) {
    if (frames == 0) return
    val current = plan ?: return

    // From the seek already in flight where there is one, so a burst of steps advances by a frame
    // each rather than all landing on the frame the mirror last reported.
    val from = seeks.target ?: readPosition()
    val landing = current.resolved.frameStepLanding(from, frames) ?: return
    seekTo(landing, SeekAccuracy.Exact)
  }

  override fun setVolume(volume: Float) {
    val level = volume.coerceIn(0f, 1f)
    this.volume = level
    handler.post { player?.volume = level }
  }

  override fun setLoopRange(range: TimeRange?) {
    loops.setLoopRange(range)
  }

  override fun setPlaybackRange(range: TimeRange?) {
    loops.setPlaybackRange(range)
  }

  override suspend fun apply(
    change: CompositionDiff,
    next: Media3PreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    // A player that reported an error never gets back to ready, which is media3's limit, so it takes a rebuild.
    val hasGraph = preview != null && !status.playerErrored
    val cost = loadCostFor(change, hasGraph) { preview?.updateParameters(next.resolved, next.bounds) == true }
    when (cost) {
      LoadCost.Parameters -> {
        // The replayable cache is what lets a paused frame take the new parameters. A playing one
        // takes them on the next frame the graph draws either way.
        status.liftRefusal()
        guard.reset()
        redraws.requestChanged()
        request.startAt?.let { seekTo(it, SeekAccuracy.Exact) }
      }
      LoadCost.Rebuild -> {
        rebuild(next, request.startAt)?.let { return it }
      }
      // Ruled out before the plan was asked for, since an equal edit never reaches a planner.
      LoadCost.Nothing -> {}
    }

    loaded = request.composition
    plan = next
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    if (request.playWhenReady) play()
    return null
  }

  /**
   * Builds a fresh graph and hands it to the player.
   *
   * @return null when the graph was built, or why it could not be.
   */
  private fun rebuild(
    next: Media3PreviewPlan,
    startAt: Duration?,
  ): PlaybackError? {
    val lowered =
      try {
        lower(next)
      } catch (refused: Media3LoweringFailure) {
        return FilmstripError.InvalidComposition(refused.reason).toPlaybackError()
      }

    platformLoads++
    seeks.framesStopped()
    // Before the status moves and before the new output size is reported, both of which resize the
    // surface under a graph that cannot fill it yet.
    // Under the cover the shutter just closed, and after it has read the surface, since fixing the
    // buffer reallocates it.
    shutter.close(surfaces.previewTarget) { surfaces.fixBuffer(surfaces.previewTarget, next.resolved.output.size) }
    val load = loads.issue()
    status.reset()
    preview = lowered
    setStatus(PlaybackStatus.Preparing)

    val start =
      startAt?.let(next.resolved::seekLandingOf)
        ?: next.resolved.heldPosition(readPosition(), loops.loopRange, isPlayWanted)
    val mediaStart = lowered.firstSlotOf(start)
    val startMs = mediaStart.toMediaMs()
    seeks.rest(start, mediaStart)
    status.platformPositionMs = startMs
    guard.reset()
    emitPosition(start)
    val multipleInputs = lowered.videoSequences > 1
    val level = volume
    handler.post {
      // A player built for a different video graph starts paused at full volume, so it takes the transport from the one
      // it replaces, a play still waiting on its replay included, and the volume the engine holds.
      val pending = seeks.takePendingPlay()
      val wanted = host.player?.playWhenReady == true || pending
      val target = host.playerFor(multipleInputs, lowered.composition.hdrMode)
      target.volume = level
      loads.handOver(load)
      target.setComposition(lowered.composition, startMs)
      heldPin.start(target, wanted, start, mediaStart, scrubMode.active)
      target.prepare()
    }
    return null
  }

  override fun fail(error: PlaybackError) {
    status.fail(error)
  }

  private companion object {
    const val THREAD_NAME = "filmstrip-player"
  }
}
