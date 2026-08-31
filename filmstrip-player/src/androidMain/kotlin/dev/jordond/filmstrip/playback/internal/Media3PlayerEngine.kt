package dev.jordond.filmstrip.playback.internal

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media3.internal.Media3LoweringFailure
import dev.jordond.filmstrip.media3.internal.Media3Preview
import dev.jordond.filmstrip.media3.internal.toMedia3Preview
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
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
 */
@ExperimentalApi
@OptIn(InternalFilmstripApi::class)
internal class Media3PlayerEngine(
  parent: CoroutineScope,
  private val context: Context,
  private val planner: Media3PreviewPlanner,
  config: PlayerConfig,
) : BasePlayerEngine(parent) {
  private val thread = HandlerThread(THREAD_NAME).apply { start() }
  private val handler = Handler(thread.looper)

  @Volatile
  private var player: CompositionPlayer? = null

  // Read from the engine's dispatcher and written on the player's, so a listener registered for a
  // player that has since been released stops hearing without waiting for a cache line.
  @Volatile
  private var epoch = 0

  private var takesMultipleInputs = false

  // Player thread only. The surface posts its attachment across rather than writing it here.
  private var surfaceView: SurfaceView? = null

  // The same view, kept where a rebuild can reach it. A swap freezes the surface on the engine's
  // dispatcher, which is not the thread the player's copy is confined to.
  @Volatile
  private var previewView: SurfaceView? = null

  private var surfaceRendered = false

  @Volatile
  private var platformState: Int = Player.STATE_IDLE

  @Volatile
  private var platformPositionMs: Long = 0

  @Volatile
  private var platformDurationMs: Long = C.TIME_UNSET

  private var mirroring = false
  private val mirrorTick = Runnable { mirrorPlayhead() }

  private var loaded: EditComposition? = null
  private var plan: Media3PreviewPlan? = null
  private var preview: Media3Preview? = null
  private var pending: PendingLoad? = null
  private var policy: PreviewQualityPolicy = config.qualityPolicy

  private var presentable = false

  // Read on the player's thread and written on the engine's. media3 keeps a size read off the
  // holder only once it has a graph to hand it to, and this is the engine's view of whether it has.
  @Volatile
  private var graphUp = false

  // The buffer the attached view's holder has been fixed to, so a plan that renders the same frame
  // as the last one leaves it alone.
  private var bufferSize: Size? = null

  private var ended = false
  private var endReported = false
  private var firstFrameEmitted = false
  private var failure: PlaybackError? = null

  private var seekCompletion: (() -> Unit)? = null
  private var seekTarget: Duration? = null
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  /**
   * How many times the media3 graph has been built or rebuilt.
   *
   * `CompositionPlayer.setComposition` reconfigures the whole pipeline on every call, so this
   * counts a decoder reinitialization. A parameters-only edit and an equal one cost nothing here.
   */
  var platformLoads: Int = 0
    private set

  /**
   * How many times the player has been pointed at a surface.
   *
   * One for the attachment, and one more each time the surface changed shape under it.
   */
  @Volatile
  var surfaceApplications: Int = 0
    private set

  /**
   * How many times a resized surface has been asked to draw again.
   *
   * The cheap half of [surfaceApplications]: media3 has the new size already and only the pixels
   * are missing.
   */
  @Volatile
  var surfaceRedraws: Int = 0
    private set

  /**
   * The accuracy the last seek really ran at.
   *
   * Not always the accuracy that was asked for. See [clampedAccuracy].
   */
  var lastSeekAccuracy: SeekAccuracy = SeekAccuracy.Exact
    private set

  /**
   * The duration media3's own timeline reports, or null before it has one.
   */
  val platformDuration: Duration? get() = platformDurationMs.takeIf { it != C.TIME_UNSET }?.milliseconds

  /**
   * Where media3 itself says the playhead is.
   */
  val platformPosition: Duration get() = platformPositionMs.milliseconds

  override val id: String = "filmstrip.media3"

  private val listener = PlayerListener(epoch)

  /**
   * What watches for the occasions media3 does not report. Reachable so a test can raise one.
   */
  val interruptions = Media3Interruptions(context) { scope.launch { onInterrupted() } }

  private val shutter = PreviewShutter(scope)

  // PlaybackSpeed is absent from CompositionPlayer's own command set, so it is never claimed.
  // AudioMonitoring is asked of the player rather than assumed, since setVolume is only real where
  // the command is available. HdrPreview is asked of the display, which is what decides whether a
  // grade survives the last step.
  override val features: PlayerFeatures =
    onPlayerThread {
      val built = buildPlayer(multipleInputs = false)
      built.addListener(listener)
      player = built
      engines[built] = this
      PlayerFeatures(
        buildSet {
          add(PlayerFeature.FrameReadback)
          add(PlayerFeature.FrameStepping)
          add(PlayerFeature.LiveParameterRedraw)
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
      colorSpace = { if (plan?.resolved?.hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709 },
      revision = { loaded?.effectsRevision() ?: 0L },
    )

  override val nativePlayer: Any? get() = player

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    supersedePending()

    val change = diff(loaded, request.composition)
    if (loadCostFor(change, preview != null) { false } == LoadCost.Nothing) {
      // Nothing at the platform level, not even a status move. A listener must not see this load.
      loaded = request.composition
      callback.onResult(SetCompositionResult.Success(plan?.resolved?.duration ?: Duration.ZERO))
      return Cancellable { }
    }

    // Lazy, so the load is recorded before it can run. A body that reached settle() first would
    // find nothing to settle and leave the caller waiting on a result that had already been decided.
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          when (val result = planner.plan(request.composition, policy)) {
            is Media3PlanResult.Refused -> {
              refuse(result.error)
            }
            is Media3PlanResult.Ready -> {
              when (val error = apply(change, result.plan, request)) {
                null -> settle(SetCompositionResult.Success(result.plan.resolved.duration))
                else -> refuse(error)
              }
            }
          }
        } catch (cancelled: CancellationException) {
          // A superseded or cancelled load is not a failed one, and the request that replaced it
          // has already resolved this caller.
          throw cancelled
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Exception,
        ) {
          // Planning reaches a prober, a resolver and a device query, any of which may throw rather
          // than refuse. Without this the caller waits forever on a load nothing will ever settle.
          refuse(
            PlaybackError.Underlying(
              PlaybackError.Underlying.NO_PLATFORM_CODE,
              broken.message ?: broken.toString(),
            ),
          )
        }
      }

    pending = PendingLoad(job, callback)
    job.start()
    return Cancellable {
      pending?.takeIf { it.job === job }?.let { it.settled = true }
      job.cancel()
    }
  }

  override fun readPosition(): Duration = platformPositionMs.milliseconds

  override fun isSeekReady(): Boolean = preview != null && platformState != Player.STATE_IDLE

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  ) {
    lastSeekAccuracy = clampedAccuracy(accuracy)

    val duration = plan?.resolved?.duration
    val target = if (duration == null) position else position.coerceIn(Duration.ZERO, duration)
    seekTarget = target
    seekCompletion = onComplete
    ended = false
    endReported = false

    handler.post {
      val standing = player
      if (standing == null) {
        scope.launch { completeSeek() }
      } else {
        standing.seekTo(target.inWholeMilliseconds)
      }
    }
  }

  override fun onPlay() {
    ended = false
    endReported = false
    handler.post { player?.playWhenReady = true }
  }

  override fun onPause() {
    handler.post { player?.playWhenReady = false }
  }

  override fun onScrubbingChanged(scrubbing: Boolean) {
    // Orthogonal to accuracy and free: it drops audio, raises the codec operating rate and skips
    // the flush a seek would otherwise cost, which is what makes a burst of exact seeks affordable.
    handler.post { player?.setScrubbingModeEnabled(scrubbing) }
  }

  /**
   * Draws into [view] from now on, replacing whatever surface the player was pointed at.
   *
   * A player rebuilt for a different video graph takes the same view with it.
   */
  fun attachSurfaceView(view: SurfaceView) {
    view.holder.addCallback(surfaceResizes)
    previewView = view

    // A view arriving after the graph takes the frame the graph is already rendering.
    scope.launch { fixBuffer(view, plan?.resolved?.output?.size) }
    handler.post {
      surfaceView = view
      player?.setVideoSurfaceView(view)
      surfaceApplications++
    }
  }

  /**
   * Stops drawing into [view], leaving a view another surface has since attached alone.
   *
   * The next surface has to earn its own first frame, which media3 raises again as it takes over
   * the player's video output.
   */
  fun detachSurfaceView(view: SurfaceView) {
    view.holder.removeCallback(surfaceResizes)
    if (previewView === view) {
      previewView = null
      scope.launch { bufferSize = null }
    }
    handler.post {
      if (surfaceView !== view) return@post
      surfaceView = null
      player?.clearVideoSurfaceView(view)
      scope.launch {
        surfaceRendered = false
        firstFrameEmitted = false
        shutter.reveal()
      }
    }
  }

  /**
   * Follows the frame held over the surface while the graph is rebuilt. See [PreviewShutter].
   *
   * @return a handle that stops the reports.
   */
  fun observeStill(listener: (Bitmap?) -> Unit): Cancellable = shutter.addListener(listener)

  override fun onRelease() {
    supersedePending()
    mirroring = false
    interruptions.dispose()
    readback.dispose()
    shutter.dispose()
    preview = null
    handler.post {
      surfaceView = null
      player?.let {
        engines.remove(it)
        it.release()
      }
      player = null
      thread.quitSafely()
    }
  }

  override fun stepFrames(frames: Int) {
    if (frames == 0) return
    val current = plan ?: return
    val rate =
      current.resolved.output.frameRate
        ?.takeIf { it > 0 } ?: return

    // From the seek already in flight where there is one, so a burst of steps advances by a frame
    // each rather than all landing on the frame the mirror last reported.
    val from = seekTarget ?: readPosition()
    val landing = from + (1.seconds / rate) * frames
    if (landing < Duration.ZERO || landing > current.resolved.duration) return
    seekTo(landing, SeekAccuracy.Exact)
  }

  override fun setVolume(volume: Float) {
    val level = volume.coerceIn(0f, 1f)
    handler.post { player?.volume = level }
  }

  override fun setLoopRange(range: TimeRange?) {
    loopRange = range
    range?.start?.takeIf { readPosition() < it }?.let { seekTo(it, SeekAccuracy.Exact) }
  }

  override fun setPlaybackRange(range: TimeRange?) {
    playbackRange = range
    range?.start?.takeIf { readPosition() < it }?.let { seekTo(it, SeekAccuracy.Exact) }
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    if (this.policy == policy) return
    this.policy = policy

    // The cap moves the rendered frame, which moves the output format, and no live parameter can
    // carry that. The graph is rebuilt against the edit already loaded.
    val composition = loaded ?: return
    loaded = null
    setComposition(SetCompositionRequest(composition)) { }
  }

  /**
   * Applies one lowered plan, rebuilding only what the change actually costs.
   *
   * @return null when the plan is loaded, or why it could not be.
   */
  private fun apply(
    change: CompositionDiff,
    next: Media3PreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    when (loadCostFor(change, preview != null) { preview?.updateParameters(next.resolved) == true }) {
      LoadCost.Parameters -> {
        // The replayable cache is what lets a paused frame take the new parameters. A playing one
        // takes them on the next frame the graph draws either way.
        failure = null
        handler.post { player?.experimentalRedrawLastFrame() }
        request.startAt?.let { seekTo(it, SeekAccuracy.Exact) }
      }
      LoadCost.Rebuild -> {
        rebuild(next, request.startAt)?.let { return it }
      }
      // Ruled out before the plan was asked for, since an equal edit never reaches a planner.
      LoadCost.Nothing -> {
        Unit
      }
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
        next.resolved.toMedia3Preview()
      } catch (refused: Media3LoweringFailure) {
        return PlaybackError.UnsupportedFormat(refused.reason)
      }

    platformLoads++
    // Before the status moves and before the new output size is reported, both of which resize the
    // surface under a graph that cannot fill it yet.
    // Under the cover the shutter just closed, and after it has read the surface, since fixing the
    // buffer reallocates it.
    shutter.close(previewView) { fixBuffer(previewView, next.resolved.output.size) }
    graphUp = false
    failure = null
    presentable = false
    ended = false
    endReported = false
    firstFrameEmitted = false
    preview = lowered
    setStatus(PlaybackStatus.Preparing)

    val startMs = (startAt ?: readPosition()).coerceIn(Duration.ZERO, next.resolved.duration).inWholeMilliseconds
    val multipleInputs = lowered.videoSequences > 1
    handler.post {
      val target = playerFor(multipleInputs)
      target.setComposition(lowered.composition, startMs)
      target.prepare()
    }
    return null
  }

  /**
   * The player that can draw a composition with this many video inputs, building a fresh one when
   * the standing player cannot.
   *
   * A video graph that takes one input is the default and the cheaper one. Handed a composition
   * with more than one video sequence it logs a warning and draws the wrong picture, so the choice
   * is made from the lowering rather than left at the default.
   */
  private fun playerFor(multipleInputs: Boolean): CompositionPlayer {
    val standing = player
    if (standing != null && takesMultipleInputs == multipleInputs) return standing

    standing?.let {
      engines.remove(it)
      it.release()
    }
    epoch++
    takesMultipleInputs = multipleInputs
    return buildPlayer(multipleInputs).also {
      it.addListener(PlayerListener(epoch))
      surfaceView?.let(it::setVideoSurfaceView)
      player = it
      engines[it] = this
    }
  }

  /**
   * Reacts to the surface changing shape under the player. See [surfaceResizeAction].
   */
  private val surfaceResizes =
    object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder): Unit = Unit

      override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
      ) {
        handler.post {
          val standing = player ?: return@post
          val attached = surfaceView ?: return@post
          if (attached.holder !== holder) return@post

          when (surfaceResizeAction(graphUp)) {
            SurfaceResize.Reapply -> {
              // Clearing first is what makes it take: setting the view it already holds is a no-op,
              // so the output has to go away before it will be built again at the shape it now has.
              standing.clearVideoSurfaceView(attached)
              standing.setVideoSurfaceView(attached)
              surfaceApplications++
            }
            SurfaceResize.Redraw -> {
              standing.experimentalRedrawLastFrame()
              surfaceRedraws++
            }
          }
        }
      }

      override fun surfaceDestroyed(holder: SurfaceHolder): Unit = Unit
    }

  /**
   * Fixes [view]'s buffer to [rendered], leaving it alone when nothing changed.
   *
   * See [previewBufferChange] for why the buffer follows the rendered frame rather than the view.
   */
  private fun fixBuffer(
    view: SurfaceView?,
    rendered: Size?,
  ) {
    val holder = view?.holder ?: return
    val target = rendered ?: return
    val next = previewBufferChange(bufferSize, target) ?: return
    bufferSize = next
    holder.setFixedSize(next.width, next.height)
  }

  private fun buildPlayer(multipleInputs: Boolean): CompositionPlayer =
    CompositionPlayer
      .Builder(context)
      .setLooper(thread.looper)
      // Audio focus handled by media3, which is what makes a focus loss reach the listener.
      .setAudioAttributes(MONITOR_AUDIO, true)
      // What buys a redrawn frame under a paused finger. It costs power and memory while the
      // preview is up, which is the whole of what a preview is for.
      .experimentalSetEnableReplayableCache(true)
      .apply { if (multipleInputs) setVideoGraphFactory(MultipleInputVideoGraph.Factory()) }
      .build()

  private fun onPlatformState(
    state: Int,
    positionMs: Long,
    durationMs: Long,
  ) {
    platformState = state
    platformPositionMs = positionMs
    platformDurationMs = durationMs

    when (state) {
      Player.STATE_READY -> {
        presentable = true
        graphUp = true
        ended = false
      }
      Player.STATE_ENDED -> {
        ended = true
      }
      else -> {
        Unit
      }
    }

    setStalled(state == Player.STATE_BUFFERING)
    publishStatus()
    reportFirstFrame()

    if (state == Player.STATE_ENDED && !endReported) {
      endReported = true
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(readPosition(), plan?.resolved?.duration ?: Duration.ZERO))
    }
  }

  private fun publishStatus() {
    val error = failure
    setStatus(
      when {
        error != null -> PlaybackStatus.Error(error)
        ended -> PlaybackStatus.Ended
        presentable -> PlaybackStatus.Ready
        preview != null -> PlaybackStatus.Preparing
        else -> PlaybackStatus.Idle
      },
    )
  }

  /**
   * Emits [PlaybackEvent.FirstFrameRendered] on the edge where the loaded composition reaches an
   * attached surface.
   *
   * media3 raises `onRenderedFirstFrame` once per surface rather than once per composition, so a
   * graph rebuilt under a surface that is already drawing has only its own readiness to go on.
   * That is the edge here, and the surface's own signal is what arms it.
   *
   * Once per composition and once per surface: a host holds a shutter closed on this, and a surface
   * let go of and taken up again, which is what backgrounding does to a preview, has to earn its
   * own signal or the shutter never lifts on the way back.
   */
  private fun reportFirstFrame() {
    if (firstFrameEmitted || !surfaceRendered || !presentable) return
    firstFrameEmitted = true
    shutter.reveal()
    emitEvent(PlaybackEvent.FirstFrameRendered)
  }

  /**
   * Reacts to an occasion outside filmstrip that took playback away.
   *
   * The transport is stopped first and the change reported second, so a listener that reads the
   * player back on the event sees it already paused.
   */
  private fun onInterrupted() {
    handler.post { player?.playWhenReady = false }
    reportExternalPlayWhenReady(false)
  }

  /**
   * Reports a load that could not be made, to the caller and to every listener.
   */
  private fun refuse(error: PlaybackError) {
    fail(error)
    settle(SetCompositionResult.Failure(error))
  }

  private fun fail(error: PlaybackError) {
    failure = error
    graphUp = false
    shutter.reveal()
    publishStatus()
    emitEvent(PlaybackEvent.Failed(error))
  }

  private fun completeSeek() {
    val completion = seekCompletion ?: return
    seekCompletion = null
    seekTarget = null
    completion()
  }

  /**
   * Reads the playhead off the player and keeps the loop and playback ranges honest.
   *
   * Nothing on `Player` pushes a position, so the engine reads one on a timer while pixels are
   * moving. [BasePlayerEngine]'s ticker publishes what this last read.
   */
  private fun mirrorPlayhead() {
    val standing = player ?: return
    val position = standing.currentPosition
    platformPositionMs = position
    scope.launch { onPlayhead(position.milliseconds) }
    if (mirroring) handler.postDelayed(mirrorTick, MIRROR_INTERVAL_MS)
  }

  private fun onPlayhead(position: Duration) {
    val loop = loopRange
    val wrapAt = loop?.endExclusive
    if (loop != null && wrapAt != null && position >= wrapAt) {
      // Moved before the seek is issued, so the next mirror reading cannot wrap the same lap twice.
      platformPositionMs = loop.start.inWholeMilliseconds
      seekTo(loop.start, SeekAccuracy.Exact)
      emitEvent(PlaybackEvent.RangeLooped(loop))
      return
    }

    val stopAt = playbackRange?.endExclusive ?: return
    if (position >= stopAt) {
      platformPositionMs = stopAt.inWholeMilliseconds
      pause()
      seekTo(stopAt, SeekAccuracy.Exact)
    }
  }

  private fun settle(result: SetCompositionResult) {
    val load = pending ?: return
    if (load.settled) return
    load.settled = true
    pending = null
    load.callback.onResult(result)
  }

  private fun supersedePending() {
    val load = pending ?: return
    pending = null
    if (load.settled) return
    load.settled = true
    load.job.cancel()
    load.callback.onResult(SetCompositionResult.Superseded)
  }

  /**
   * Runs [block] on the player's own thread and waits for its answer.
   *
   * Only construction needs this. Everything after it is posted and nothing waits.
   */
  private fun <T> onPlayerThread(block: () -> T): T {
    var outcome: Result<T>? = null
    val done = CountDownLatch(1)
    handler.post {
      outcome = runCatching(block)
      done.countDown()
    }
    done.await()
    return checkNotNull(outcome).getOrThrow()
  }

  /**
   * Everything media3 pushes, marshalled from the player's thread onto the engine's.
   *
   * A player released and rebuilt for a different video graph leaves its own callbacks queued
   * behind it, so each listener carries the generation it was registered for and a stale one is
   * dropped rather than moving the snapshot of a graph that is gone.
   */
  private inner class PlayerListener(
    private val generation: Int,
  ) : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      val position = player?.currentPosition ?: 0
      val duration = player?.duration ?: C.TIME_UNSET
      dispatch { onPlatformState(playbackState, position, duration) }
    }

    override fun onPlayWhenReadyChanged(
      playWhenReady: Boolean,
      reason: Int,
    ) {
      dispatch {
        when {
          reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> Unit
          reachedEndOfMedia(reason) -> setPlayWhenReady(playWhenReady)
          isInterruption(reason) -> reportExternalPlayWhenReady(playWhenReady)
          else -> setPlayWhenReady(playWhenReady)
        }
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (generation != epoch) return
      if (isPlaying) {
        if (!mirroring) {
          mirroring = true
          handler.post(mirrorTick)
        }
      } else {
        mirroring = false
      }
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        return
      }
      val landed = newPosition.positionMs
      dispatch {
        platformPositionMs = landed
        completeSeek()
      }
    }

    override fun onRenderedFirstFrame() {
      dispatch {
        surfaceRendered = true
        reportFirstFrame()
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      val reported = error.toPlaybackError()
      dispatch { fail(reported) }
    }

    private fun dispatch(block: () -> Unit) {
      if (generation != epoch) return
      scope.launch { if (generation == epoch) block() }
    }
  }

  /**
   * One outstanding [setComposition], so a later request can supersede it exactly once.
   */
  private class PendingLoad(
    val job: Job,
    val callback: SetCompositionCallback,
  ) {
    var settled: Boolean = false
  }

  private companion object {
    const val THREAD_NAME = "filmstrip-player"

    // Roughly display rate, which is the ceiling. positionFlow(tick) snaps it down to whatever a
    // collector asked for.
    const val MIRROR_INTERVAL_MS = 16L

    val MONITOR_AUDIO: AudioAttributes =
      AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()
  }
}

/**
 * Whether the display a preview draws onto advertises an HDR format.
 *
 * The graph hands its surface BT.2020 PQ frames whatever is on the other end of it, so the panel is
 * what decides whether the grade arrives or is tone mapped on the way.
 */
private fun displayShowsHdr(context: Context): Boolean {
  val display =
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    display.mode.supportedHdrTypes.isNotEmpty()
  } else {
    @Suppress("DEPRECATION")
    display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
  }
}

/**
 * The engine driving [player], or null when filmstrip did not build it.
 *
 * Engines register on construction and drop out on release, so a surface holding only the platform
 * player from [dev.jordond.filmstrip.player.VideoPlayer.nativePlayer] can find the engine behind
 * it. Registered on the player's thread and read from whichever thread a surface attaches on.
 */
@androidx.annotation.OptIn(ExperimentalApi::class)
internal fun engineFor(player: CompositionPlayer): Media3PlayerEngine? = engines[player]

private val engines = ConcurrentHashMap<CompositionPlayer, Media3PlayerEngine>()
