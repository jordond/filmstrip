package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.player.PlaybackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Runs one ffmpeg process at a time and presents its frames against the clock.
 *
 * Each [start] replaces whatever process was running with one opened at a new position, and moves a generation that
 * every step of the old pump checks, so nothing an old pump reads after that reaches the screen. Where a run ends,
 * wraps or lands is put to [ends].
 *
 * @param scope The engine's dispatcher, which every pump runs on.
 * @param planner Opens a process on the plan the engine is holding.
 * @param plan What the engine last lowered, or null with nothing loaded.
 * @param composition The edit that plan came from.
 * @param wanted Whether playback is wanted, which a paused presenter waits on.
 * @param ends Decides what happens at the landing and the end of each pump.
 * @param show Puts a frame on screen and moves the playhead onto it.
 * @param fail Reports an error that stopped a process from opening.
 * @param failProcess Reports a process that exited with a status and its stderr instead of rendering to its end.
 */
internal class FfmpegPump(
  private val scope: CoroutineScope,
  private val planner: FfmpegPreviewPlanner,
  private val plan: () -> PreviewPlan?,
  private val composition: () -> EditComposition?,
  private val wanted: StateFlow<Boolean>,
  private val ends: FfmpegRangeEnd,
  private val show: (PreviewFrame) -> Unit,
  private val fail: (PlaybackError) -> Unit,
  private val failProcess: (status: Int, errors: String) -> Unit,
) {
  private var job: Job? = null
  private val retired = mutableListOf<Job>()
  private var stream: PreviewStream? = null
  private var generation = 0

  // The generation of the pump parked on a pause, or none.
  private val parked = MutableStateFlow(NONE_PARKED)

  /**
   * Whether a pump is still running.
   *
   * A pump that ran to the end left its process closed behind it, so this reads false until the next [start].
   */
  val isRunning: Boolean get() = job?.isActive == true

  /**
   * Stops the running process and opens another at [from].
   *
   * The new pump presents nothing until its own process has opened, and a pump this replaces presents nothing more.
   */
  fun start(from: Duration) {
    stop()
    val token = ++generation
    job = scope.launch { run(token, from) }
  }

  /**
   * Stops the running process, if there is one, and closes its stream.
   *
   * The generation moves before the stream closes, so the pump reading it sees the stop as its own and reports no
   * failure.
   */
  fun stop() {
    generation++
    retired.removeAll { it.isCompleted }
    job?.let {
      it.cancel()
      retired += it
    }
    job = null
    stream?.close()
    stream = null
  }

  /**
   * Suspends until every pump [stop] replaced has finished, and forgets them.
   *
   * A replaced pump is cancelled but can still be running its cleanup, so anything it was going to report has been
   * reported once this returns. A pump is only forgotten once it has been joined, so a second caller waiting at the
   * same time waits it out too. The pump running now is left alone.
   */
  suspend fun awaitRetired() {
    while (retired.isNotEmpty()) {
      val replaced = retired.toList()
      replaced.forEach { it.join() }
      retired.removeAll(replaced)
    }
  }

  /**
   * Suspends until no pump is running and every pump [stop] replaced has finished.
   *
   * A pump that ends by calling [start] itself, the way a redirected wrap does, leaves that one current, so this waits
   * on whichever pump is current until one finishes without a successor. Only a pump [start] has already made current
   * is followed. A seek the engine has handed over that has not called [start] yet is the engine's to wait on. A pump
   * parked on a pause never finishes, so this only returns once playback has ended, failed or been stopped.
   */
  suspend fun awaitIdle() {
    while (true) {
      awaitRetired()
      val current = job?.takeUnless { it.isCompleted } ?: break
      current.join()
    }
  }

  /**
   * Suspends until every pump [stop] replaced has finished and the current one has finished or parked on a pause.
   *
   * A parked pump has shown the frame it landed on and reads nothing more until playback is wanted, so nothing it
   * would report is still on its way. A pump that finishes by opening another is followed onto that one, the way
   * [awaitIdle] follows it. Never returns while playback runs, so a caller bounds the wait.
   */
  suspend fun awaitQuiet() {
    while (true) {
      awaitRetired()
      val current = job?.takeUnless { it.isCompleted } ?: return
      val token = generation
      if (parked.value == token) return
      coroutineScope {
        val parks = launch { parked.first { it == token } }
        select {
          current.onJoin { }
          parks.onJoin { }
        }
        parks.cancel()
      }
    }
  }

  private suspend fun run(
    token: Int,
    from: Duration,
  ) {
    val current = plan() ?: return
    val edit = composition() ?: return

    val opened = planner.open(current, edit, from)
    if (token != generation) {
      (opened as? PreviewStreamResult.Opened)?.stream?.close()
      return
    }

    val open =
      when (opened) {
        is PreviewStreamResult.Refused -> {
          fail(opened.error.toPlaybackError())
          ends.settleLanding()
          return
        }
        is PreviewStreamResult.Opened -> {
          opened.stream
        }
      }

    stream = open
    val frames = Channel<PreviewFrame>(LOOK_AHEAD)

    try {
      coroutineScope {
        // The reader runs ahead of the presenter by the look-ahead and then blocks on a full
        // channel, which fills the pipe, which stalls ffmpeg. Coarse flow control, and free.
        val reader = launch { read(open, current.frameRate, frames) }
        // The status is read once the frames run out, while the look-ahead still plays, so a child slow to be reaped
        // costs the frame clock nothing.
        val exit =
          async {
            reader.join()
            open.exitStatus()
          }
        present(token, from, current, open, exit, frames)
        reader.cancel()
        exit.cancel()
      }
    } finally {
      // Closing here as well as in stop is what covers a scope cancelled out from under the
      // engine, where nothing calls back in to release the process.
      if (stream === open) stream = null
      open.close()
    }
  }

  private suspend fun read(
    stream: PreviewStream,
    frameRate: Int,
    frames: SendChannel<PreviewFrame>,
  ) {
    var slot = outputSlotOf(stream.startPosition, frameRate)
    try {
      while (true) {
        val pixels = stream.next() ?: break
        frames.send(PreviewFrame(outputSlotTime(slot, frameRate), pixels))
        slot++
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
   *
   * @param current The plan the pump was opened on, whose grid the frames and the range ends are counted on.
   * @param stream The stream [frames] is read off, whose stderr says why a failed process stopped.
   * @param exit How the stream's process exited, read as soon as [frames] runs out.
   */
  private suspend fun present(
    token: Int,
    from: Duration,
    current: PreviewPlan,
    stream: PreviewStream,
    exit: Deferred<Int?>,
    frames: ReceiveChannel<PreviewFrame>,
  ) {
    var anchor: TimeMark? = null
    var anchorAt = Duration.ZERO
    var landed = false
    val half = current.frameStep / 2

    for (frame in frames) {
      if (token != generation) return
      // Frames before the target only arrive on a composition the graph could not window with an
      // input seek, which opens at its head and is read forward instead.
      if (frame.position < from - half) continue

      if (!landed) {
        ends.redirectOf(current)?.let { slot ->
          ends.redirect(slot, current)
          return
        }
        landed = true
        anchor = TimeSource.Monotonic.markNow()
        anchorAt = frame.position
        show(frame)
        // The frame the seek asked for is on screen, so the request that asked for it is answered
        // whether or not playback goes on from here.
        ends.settleLanding()
        continue
      }

      if (!wanted.value) {
        anchor = null
        parked.value = token
        try {
          wanted.first { it }
        } finally {
          if (parked.value == token) parked.value = NONE_PARKED
        }
        if (token != generation) return
      }
      if (ends.reachedEnd(frame.position, current)) return

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
    // A stop or seek of ours moves the generation before it closes the stream, and a closed stream reads back no status,
    // so only a process that failed on its own is reported here.
    val status = exit.await()
    if (token != generation) return
    if (status != null && status != 0) {
      failProcess(status, stream.errors())
      return
    }
    ends.ranOut(landed, current)
  }

  private companion object {
    // Measured: eight frames covers the worst cold spawn seen with a filter graph attached, and
    // costs about a millisecond a frame to fill once the process is warm.
    const val LOOK_AHEAD = 8

    // No pump's generation, since every start moves it past zero.
    const val NONE_PARKED = 0
  }
}
