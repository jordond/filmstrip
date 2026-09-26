package dev.jordond.filmstrip.playback.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.effectsRevision
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
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
 */
@ExperimentalApi
internal class Media3PlayerEngine(
  parent: CoroutineScope,
  private val context: Context,
  private val planner: Media3PreviewPlanner,
  config: PlayerConfig,
) : BasePlayerEngine(parent) {
  private val thread = HandlerThread(THREAD_NAME).apply { start() }
  private val handler = Handler(thread.looper)

  private var loaded: EditComposition? = null
  private var plan: Media3PreviewPlan? = null
  private var preview: Media3Preview? = null
  private val pending = PendingLoad()
  private var policy: PreviewQualityPolicy = config.qualityPolicy

  private val guard = WrapGuard()

  private val shutter = PreviewShutter(scope)

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
      emitEvent = ::emitEvent,
    )

  private val surfaces: Media3SurfaceManager =
    Media3SurfaceManager(
      scope = scope,
      handler = handler,
      player = { player },
      rendered = { plan?.resolved?.output?.size },
      graphUp = { status.graphUp },
      onDetached = status::onSurfaceDetached,
    )

  private val seeks: Media3SeekCoordinator =
    Media3SeekCoordinator(
      scope = scope,
      handler = handler,
      player = { player },
      resolved = { plan?.resolved },
      guard = guard,
      platformPositionMs = { status.platformPositionMs },
      loopWrap = { loops.wrap() },
      onIssued = status::clearEnd,
    )

  private val loops: Media3LoopRangeController =
    Media3LoopRangeController(
      scope = scope,
      handler = handler,
      player = { player },
      resolved = { plan?.resolved },
      guard = guard,
      playhead = ::readPosition,
      recordPosition = { status.platformPositionMs = it },
      seekTo = { seekTo(it, SeekAccuracy.Exact) },
      seekToWrap = ::seekToWrap,
      pause = ::pause,
      onLooped = { range, landing ->
        emitEvent(PlaybackEvent.RangeLooped(range))
        emitPosition(landing)
      },
    )

  private val host =
    Media3PlayerHost(
      context = context,
      scope = scope,
      thread = thread,
      status = status,
      seeks = seeks,
      loops = loops,
      surfaces = surfaces,
      onBuilt = { engines[it] = this },
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
   * How many times the player has been pointed at a surface.
   *
   * One for the attachment, and one more each time the surface changed shape under it.
   */
  val surfaceApplications: Int get() = surfaces.applications

  /**
   * How many times a resized surface has been asked to draw again.
   *
   * The cheap half of [surfaceApplications]: media3 has the new size already and only the pixels
   * are missing.
   */
  val surfaceRedraws: Int get() = surfaces.redraws

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

  override val id: String = "filmstrip.media3"

  /**
   * What watches for the occasions media3 does not report. Reachable so a test can raise one.
   */
  val interruptions = Media3Interruptions(context) { scope.launch { status.onInterrupted() } }

  // PlaybackSpeed is absent from CompositionPlayer's own command set, so it is never claimed.
  // AudioMonitoring is asked of the player rather than assumed, since setVolume is only real where
  // the command is available. HdrPreview is asked of the display, which is what decides whether a
  // grade survives the last step.
  override val features: PlayerFeatures =
    host.start { built ->
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
      revision = { loaded?.effectsRevision() ?: 0L },
    )

  override val nativePlayer: Any? get() = player

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending.supersede()

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
                null -> pending.settle(SetCompositionResult.Success(result.plan.resolved.duration))
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

    val handle = pending.track(job, callback)
    job.start()
    return handle
  }

  override fun readPosition(): Duration = seeks.position()

  override fun isSeekReady(): Boolean = preview != null && status.platformState != Player.STATE_IDLE

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    seeks.seek(position, accuracy, wrap = isWrapSeek, onComplete)
  }

  override fun onPlay() {
    status.clearEnd()
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
    surfaces.attach(view)
  }

  /**
   * Stops drawing into [view], leaving a view another surface has since attached alone.
   *
   * The next surface has to earn its own first frame, which media3 raises again as it takes over
   * the player's video output.
   */
  fun detachSurfaceView(view: SurfaceView) {
    surfaces.detach(view)
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

  override fun stepFrames(frames: Int) {
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
    handler.post { player?.volume = level }
  }

  override fun setLoopRange(range: TimeRange?) {
    loops.setLoopRange(range)
  }

  override fun setPlaybackRange(range: TimeRange?) {
    loops.setPlaybackRange(range)
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
    when (loadCostFor(change, preview != null) { preview?.updateParameters(next.resolved, next.bounds) == true }) {
      LoadCost.Parameters -> {
        // The replayable cache is what lets a paused frame take the new parameters. A playing one
        // takes them on the next frame the graph draws either way.
        status.clearFailure()
        guard.reset()
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
        next.resolved.toMedia3Preview(next.bounds)
      } catch (refused: Media3LoweringFailure) {
        return PlaybackError.UnsupportedFormat(refused.reason)
      }

    platformLoads++
    // Before the status moves and before the new output size is reported, both of which resize the
    // surface under a graph that cannot fill it yet.
    // Under the cover the shutter just closed, and after it has read the surface, since fixing the
    // buffer reallocates it.
    shutter.close(surfaces.previewView) { surfaces.fixBuffer(surfaces.previewView, next.resolved.output.size) }
    status.reset()
    preview = lowered
    setStatus(PlaybackStatus.Preparing)

    val start = next.resolved.seekLandingOf(startAt ?: readPosition())
    val startMs = start.toMediaMs()
    seeks.landing = start
    guard.reset()
    val multipleInputs = lowered.videoSequences > 1
    handler.post {
      val target = host.playerFor(multipleInputs)
      target.setComposition(lowered.composition, startMs)
      target.prepare()
    }
    return null
  }

  /**
   * Reports a load that could not be made, to the caller and to every listener.
   */
  private fun refuse(error: PlaybackError) {
    status.fail(error)
    pending.settle(SetCompositionResult.Failure(error))
  }

  private companion object {
    const val THREAD_NAME = "filmstrip-player"
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
