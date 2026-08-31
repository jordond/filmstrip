package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.ffmpeg.PreviewStreamResult
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
@OptIn(InternalFilmstripApi::class)
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
  private var loopRange: TimeRange? = null
  private var playbackRange: TimeRange? = null

  private var pump: Job? = null
  private var stream: PreviewStream? = null
  private var generation = 0
  private var landing: (() -> Unit)? = null
  private var pending: PendingLoad? = null

  // Read by the presenter rather than by the transport, because a paused presenter is parked inside
  // a suspend and a snapshot flag would leave it parked.
  private val wanted = MutableStateFlow(false)

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
    supersedePending()

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
            settle(SetCompositionResult.Failure(result.error))
          }
          is FfmpegPlanResult.Ready -> {
            apply(change, result.plan, request)
            settle(SetCompositionResult.Success(result.plan.resolved.duration))
          }
        }
      }

    pending = PendingLoad(job, callback)
    return Cancellable {
      pending?.takeIf { it.job === job }?.let { it.settled = true }
      job.cancel()
    }
  }

  override fun readPosition(): Duration = position

  override fun isSeekReady(): Boolean = plan != null

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  ) {
    val target = snap(position)
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
    // rather than a resume.
    if (pump?.isActive != true && plan != null) startPump(position, null)
  }

  override fun onPause() {
    wanted.value = false
  }

  override fun onRelease() {
    supersedePending()
    landing = null
    wanted.value = false
    stopPump()
    presented = null
  }

  override fun stepFrames(frames: Int) {
    if (frames == 0) return
    val current = plan ?: return
    val landing = position + current.frameStep * frames
    if (landing < Duration.ZERO || landing > current.resolved.duration) return
    // A step is a seek on this backend, and an expensive one: the process cannot move, so it is
    // replaced.
    seekTo(landing, SeekAccuracy.Exact)
  }

  /**
   * Does nothing.
   *
   * The pump renders video and opens no audio device, which is why
   * [PlayerFeature.AudioMonitoring] is not among [features].
   */
  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?) {
    loopRange = range
  }

  override fun setPlaybackRange(range: TimeRange?) {
    playbackRange = range
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
    onLanded: (() -> Unit)?,
  ) {
    settleLanding()
    landing = onLanded
    stopPump()

    val token = ++generation
    pump = scope.launch { runPump(token, from) }
  }

  private fun stopPump() {
    generation++
    pump?.cancel()
    pump = null
    stream?.close()
    stream = null
  }

  private suspend fun runPump(
    token: Int,
    from: Duration,
  ) {
    val current = plan ?: return
    val composition = loaded ?: return

    val opened = planner.open(current, composition, from)
    if (token != generation) {
      (opened as? PreviewStreamResult.Opened)?.stream?.close()
      return
    }

    val open =
      when (opened) {
        is PreviewStreamResult.Refused -> {
          fail(opened.error.toPlaybackError())
          settleLanding()
          return
        }
        is PreviewStreamResult.Opened -> {
          opened.stream
        }
      }

    stream = open
    val step = current.frameStep
    val frames = Channel<PreviewFrame>(LOOK_AHEAD)

    try {
      coroutineScope {
        // The reader runs ahead of the presenter by the look-ahead and then blocks on a full
        // channel, which fills the pipe, which stalls ffmpeg. Coarse flow control, and free.
        val reader = launch { read(open, step, frames) }
        present(token, from, step, frames)
        reader.cancel()
      }
    } finally {
      // Closing here as well as in stopPump is what covers a scope cancelled out from under the
      // engine, where nothing calls back in to release the process.
      if (stream === open) stream = null
      open.close()
    }
  }

  private suspend fun read(
    stream: PreviewStream,
    step: Duration,
    frames: SendChannel<PreviewFrame>,
  ) {
    var at = stream.startPosition
    try {
      while (true) {
        val pixels = stream.next() ?: break
        frames.send(PreviewFrame(at, pixels))
        at += step
      }
    } finally {
      frames.close()
    }
  }

  /**
   * Paces the frames against the clock and shows them.
   *
   * The anchor is taken on the frame that lands rather than when the process started, so the wait
   * before each frame is measured from a frame that was actually on screen. It is dropped whenever
   * playback parks, since the time spent paused is not time the composition advanced through.
   */
  private suspend fun present(
    token: Int,
    from: Duration,
    step: Duration,
    frames: ReceiveChannel<PreviewFrame>,
  ) {
    var anchor: TimeMark? = null
    var anchorAt = Duration.ZERO
    var landed = false
    val half = step / 2

    for (frame in frames) {
      if (token != generation) return
      // Frames before the target only arrive on a composition the graph could not window with an
      // input seek, which opens at its head and is read forward instead.
      if (frame.position < from - half) continue

      if (!landed) {
        landed = true
        anchor = TimeSource.Monotonic.markNow()
        anchorAt = frame.position
        show(frame)
        // The frame the seek asked for is on screen, so the request that asked for it is answered
        // whether or not playback goes on from here.
        settleLanding()
        continue
      }

      if (!wanted.value) {
        anchor = null
        wanted.first { it }
        if (token != generation) return
      }
      if (reachedEnd(frame.position)) return

      val mark =
        anchor ?: TimeSource.Monotonic.markNow().also {
          anchor = it
          anchorAt = frame.position
        }
      val due = frame.position - anchorAt
      val elapsed = mark.elapsedNow()
      if (due > elapsed) delay(due - elapsed)
      if (token != generation) return

      show(frame)
    }

    if (token != generation) return
    finish(landed)
  }

  /**
   * Wraps a loop range, or stops at the end of a playback range.
   *
   * @return true when [at] is outside what may play, so the caller stops presenting.
   */
  private fun reachedEnd(at: Duration): Boolean {
    loopRange?.takeIf { at !in it && at >= it.start }?.let { range ->
      seekTo(range.start, SeekAccuracy.Exact)
      emitEvent(PlaybackEvent.RangeLooped(range))
      return true
    }
    playbackRange?.takeIf { at !in it && at >= it.start }?.let {
      finish(landed = true)
      return true
    }
    return false
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
   * A pump that ran out with playback unwanted is a seek that landed on the tail rather than a
   * play-through finishing, so it moves nothing but the status it needs to leave presentable.
   */
  private fun finish(landed: Boolean) {
    val current = plan ?: return
    if (!wanted.value) {
      if (!landed) {
        position = position.coerceAtMost(current.resolved.duration)
        setStatus(PlaybackStatus.Ready)
        settleLanding()
      }
      return
    }

    position = playbackRange?.endExclusive ?: current.resolved.duration
    wanted.value = false
    setStatus(PlaybackStatus.Ended)
    setPlayWhenReady(false)
    emitPosition(position)
    emitEvent(PlaybackEvent.Ended(position, current.resolved.duration))
    settleLanding()
  }

  /**
   * The frame grid position [at] belongs to, clamped inside the composition.
   *
   * Every frame this backend presents sits on the grid the lowered frame rate defines, so a request
   * that lands between two frames resolves to the one a host is looking at.
   */
  private fun snap(at: Duration): Duration {
    val current = plan ?: return Duration.ZERO
    val step = current.frameStep
    val last = ((current.resolved.duration / step).toInt() - 1).coerceAtLeast(0)
    return step * (at / step).roundToInt().coerceIn(0, last)
  }

  private fun settleLanding() {
    val callback = landing ?: return
    landing = null
    callback()
  }

  private fun fail(error: PlaybackError) {
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
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
   * One outstanding [setComposition], so a later request can supersede it exactly once.
   */
  private class PendingLoad(
    val job: Job,
    val callback: SetCompositionCallback,
  ) {
    var settled: Boolean = false
  }

  private companion object {
    // Measured: it clears the cold spawn cost on most content before it fires, and stays under the
    // threshold where a settle starts reading as lag.
    val SCRUB_DEBOUNCE = 120.milliseconds

    // Measured: eight frames covers the worst cold spawn seen with a filter graph attached, and
    // costs about a millisecond a frame to fill once the process is warm.
    const val LOOK_AHEAD = 8
  }
}
