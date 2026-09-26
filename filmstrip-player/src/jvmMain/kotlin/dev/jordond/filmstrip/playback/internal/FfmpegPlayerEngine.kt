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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The desktop preview engine, driving ffmpeg as a frame pump.
 *
 * One process renders the composition and writes raw frames down a pipe. The process runs the
 * `-filter_complex` an export of the same edit runs, out of the same lowering, so what a host draws
 * is what the file would contain rather than an approximation of it.
 *
 * ffmpeg settles a seek as the process starts and takes no seek on its stdin, so the transport is
 * built around that: playback is one long-lived process read forward, and a seek kills it and
 * spawns another. Reading forward costs about a millisecond a frame and a respawn costs tens to
 * hundreds, which is why the two are not the same operation here.
 *
 * Video only. [PlayerFeature.AudioMonitoring] is not reported and [setVolume] does nothing, so a
 * host reads the feature rather than wondering why the slider is silent.
 *
 * Not thread safe, and neither is [BasePlayerEngine]. Everything is confined to [scope].
 *
 * @param parent The scope this engine's own is a child of, on the dispatcher everything is
 *   confined to.
 * @param planner Lowers an edit the way an export of it would be lowered, and opens pumps on it.
 * @param config How the player was built.
 */
internal class FfmpegPlayerEngine(
  parent: CoroutineScope,
  private val planner: FfmpegPreviewPlanner,
  config: PlayerConfig,
) : BasePlayerEngine(parent) {
  private var loaded: EditComposition? = null
  private var plan: FfmpegPreviewPlan? = null
  private var policy: PreviewQualityPolicy = config.qualityPolicy

  private var position: Duration = Duration.ZERO
  private var presented: PreviewFrame? = null
  private var firstFrameEmitted = false

  private val pending = PendingLoad()

  // Read by the presenter rather than by the transport, because a paused presenter is parked inside
  // a suspend and a snapshot flag would leave it parked.
  private val wanted = MutableStateFlow(false)

  private val ends =
    FfmpegRangeEnd(
      presented = { presented },
      position = { position },
      moveTo = { position = it },
      wanted = wanted,
      startPump = ::startPump,
      seekToWrap = ::seekToWrap,
      looped = { emitEvent(PlaybackEvent.RangeLooped(it)) },
      finish = ::finish,
    )

  private val pump =
    FfmpegPump(
      scope = scope,
      planner = planner,
      plan = { plan },
      composition = { loaded },
      wanted = wanted,
      ends = ends,
      show = ::show,
      fail = ::fail,
      failProcess = ::failProcess,
    )

  /**
   * How many times a pump has been lowered and spawned for a newly set composition.
   *
   * A parameter change is a new graph here, so it counts. Setting an equal composition does not.
   */
  var platformLoads: Int = 0
    private set

  override val id: String = "filmstrip.ffmpeg"

  // No AudioMonitoring: nothing here opens an audio device. No LiveParameterRedraw: a parameter is
  // filter graph text, so changing one is a new graph and a new process rather than a value swapped
  // under a running render. No HdrPreview: the pump converts a grade straight to RGB. No
  // PlaybackSpeed: the graph pins the frame rate and a rate change would be another respawn.
  override val features: PlayerFeatures =
    PlayerFeatures(setOf(PlayerFeature.FrameReadback, PlayerFeature.FrameStepping))

  /**
   * Null. There is no desktop player object: the frames arrive on a pipe and a surface draws them
   * through [readback].
   */
  override val nativePlayer: Any? = null

  override val readback: PreviewFrameReadback =
    FfmpegFrameReadback(
      scope = scope,
      planner = planner,
      plan = { plan },
      composition = { loaded },
      presented = { presented },
      snap = ::snap,
    )

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending.supersede()

    val change = diff(loaded, request.composition)
    if (change == CompositionDiff.Equal && plan != null) {
      // Nothing at the platform level, not even a status move. A listener must not see this load.
      loaded = request.composition
      callback.onResult(SetCompositionResult.Success(plan?.resolved?.duration ?: Duration.ZERO))
      return Cancellable { }
    }

    val job =
      scope.launch {
        when (val result = planner.plan(request.composition, policy)) {
          is FfmpegPlanResult.Refused -> {
            fail(result.error)
            pending.settle(SetCompositionResult.Failure(result.error))
          }
          is FfmpegPlanResult.Ready -> {
            apply(change, result.plan, request)
            pending.settle(SetCompositionResult.Success(result.plan.resolved.duration))
          }
        }
      }

    return pending.track(job, callback)
  }

  override fun readPosition(): Duration = position

  override fun isSeekReady(): Boolean = plan != null

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val target = snap(position)
    ends.seekIssued(wrap = isWrapSeek, onComplete = onComplete)
    scope.launch {
      // A relaxed seek is a finger moving, and every one of them costs a process. Waiting the
      // debounce out first is what bounds the respawns: the chase coalesces whatever arrives while
      // this waits and dispatches only the newest of them next.
      if (accuracy == SeekAccuracy.Nearest) delay(SCRUB_DEBOUNCE)
      this@FfmpegPlayerEngine.position = target
      startPump(target, onComplete)
    }
  }

  override fun onPlay() {
    wanted.value = true
    // A pump that ran to the end left its process closed behind it, so playing again is a spawn
    // rather than a resume. It also left the playhead on the end, past the last slot, so the spawn
    // opens on that slot rather than on the end.
    if (!pump.isRunning && plan != null) startPump(replayFrom(), null)
  }

  override fun onPause() {
    wanted.value = false
  }

  override fun onRelease() {
    pending.supersede()
    ends.dropLanding()
    wanted.value = false
    pump.stop()
    presented = null
  }

  override fun stepFrames(frames: Int) {
    if (frames == 0) return
    val target = plan?.resolved?.frameStepLanding(position, frames) ?: return
    // A step is a seek on this backend, and an expensive one: the process cannot move, so it is
    // replaced.
    seekTo(target, SeekAccuracy.Exact)
  }

  /**
   * Does nothing.
   *
   * The pump renders video and opens no audio device, which is why
   * [PlayerFeature.AudioMonitoring] is not among [features].
   */
  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?) {
    ends.setLoopRange(range)
  }

  override fun setPlaybackRange(range: TimeRange?) {
    ends.playbackRange = range
    range?.start?.takeIf { position < it }?.let { seekTo(it, SeekAccuracy.Exact) }
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    if (this.policy == policy) return
    this.policy = policy

    // The cap moves the rendered frame, which moves the output format, which is a different graph
    // and so a different process. The edit already loaded is lowered again against the new cap.
    val composition = loaded ?: return
    loaded = null
    setComposition(SetCompositionRequest(composition), { })
  }

  /**
   * Applies one lowered plan and puts a pump on it.
   *
   * A parameter change is a new graph, so it costs a respawn either way. What it does not cost is
   * the timeline: the playhead stays where it was and nothing goes back through
   * [PlaybackStatus.Preparing], so the preview redraws where it stood rather than restarting.
   */
  private fun apply(
    change: CompositionDiff,
    next: FfmpegPreviewPlan,
    request: SetCompositionRequest,
  ) {
    val parametersOnly = change == CompositionDiff.ParametersOnly && plan != null
    platformLoads++
    loaded = request.composition
    plan = next
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)

    if (!parametersOnly) {
      firstFrameEmitted = false
      setStatus(PlaybackStatus.Preparing)
    }

    position = snap(request.startAt ?: if (parametersOnly) position else Duration.ZERO)
    ends.reset()
    startPump(position, null)
    if (request.playWhenReady) play()
  }

  /**
   * Replaces the running process with one opened at [from].
   *
   * A seek waiting on the process this replaces is settled before it goes, so a caller counting
   * completions against requests is never left waiting on a frame that is no longer coming.
   */
  private fun startPump(
    from: Duration,
    onLanded: ((Duration) -> Unit)?,
  ) {
    ends.hold(onLanded)
    pump.start(from)
  }

  private fun show(frame: PreviewFrame) {
    presented = frame
    position = frame.position
    setStatus(PlaybackStatus.Ready)
    emitPosition(frame.position)
    if (firstFrameEmitted) return
    firstFrameEmitted = true
    emitEvent(PlaybackEvent.FirstFrameRendered)
  }

  /**
   * Reports the end of the stream.
   *
   * A pump that ran out with playback unwanted is not a play-through finishing, so no end is reported. When it also
   * showed no frame, the paused seek that opened it is still waiting, since the seek landed near the end and the stream
   * ran out first. That seek lands where the playhead holds, no later than the end, and the status moves only as far as
   * it needs to leave the preview presentable. A process that failed never gets here, [failProcess] reports it.
   */
  private fun finish(landed: Boolean) {
    val current = plan ?: return
    if (!wanted.value) {
      if (!landed) {
        position = position.coerceAtMost(current.resolved.duration)
        setStatus(PlaybackStatus.Ready)
        ends.settleLanding()
      }
      return
    }

    // A seek still waiting here opened a pump that ended before it showed a frame without its process failing. The
    // playhead still holds the slot the seek asked for and it lands there. Settling before the end is reported leaves
    // the end as the last playhead a listener sees.
    ends.settleLanding()
    position = ends.playbackRange?.endExclusive ?: current.resolved.duration
    wanted.value = false
    setStatus(PlaybackStatus.Ended)
    setPlayWhenReady(false)
    emitPosition(position)
    emitEvent(PlaybackEvent.Ended(position, current.resolved.duration))
  }

  /**
   * Reports a pump whose process exited with [status] instead of rendering to its end.
   *
   * A seek still waiting on the pump lands where the playhead holds, the way [finish] lands it, and playback stops before
   * the failure is reported. The error carries [status] as its platform code and the tail of [errors] as its message.
   */
  private fun failProcess(
    status: Int,
    errors: String,
  ) {
    ends.settleLanding()
    wanted.value = false
    setPlayWhenReady(false)
    val exited = "ffmpeg exited with status $status."
    val tail = errors.trim()
    fail(PlaybackError.Underlying(status, if (tail.isEmpty()) exited else "$exited\n$tail"))
  }

  private fun snap(at: Duration): Duration = plan?.resolved?.seekLandingOf(at) ?: Duration.ZERO

  // The slot the playhead lands on, held to the last one a playback range carries.
  private fun replayFrom(): Duration {
    val rate = plan?.frameRate ?: return Duration.ZERO
    val slot = ends.playbackRange?.let { lastSlotOf(it, rate) } ?: return snap(position)
    return snap(position.coerceAtMost(outputSlotTime(slot, rate)))
  }

  private fun fail(error: PlaybackError) {
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
  }

  private companion object {
    // Measured: it clears the cold spawn cost on most content before it fires, and stays under the
    // threshold where a settle starts reading as lag.
    val SCRUB_DEBOUNCE = 120.milliseconds
  }
}
