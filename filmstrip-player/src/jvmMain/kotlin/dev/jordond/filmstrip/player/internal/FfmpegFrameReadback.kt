package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * One frame the pump delivered, and where in the composition it came from.
 */
internal class PreviewFrame(
  val position: Duration,
  val pixels: ByteArray,
)

/**
 * Reads forward to the frame on the output slot [at] lands on at [frameRate].
 *
 * A windowed stream opens on that slot, so this takes its first frame and stops. A stream the input seek could not
 * window opens at its head and is read forward one slot a frame until it gets there. Either way the frame carries the
 * time of its own slot.
 *
 * @return The frame, or null when the stream ends before it.
 */
internal suspend fun PreviewStream.frameAt(
  at: Duration,
  frameRate: Int,
): PreviewFrame? {
  val target = outputSlotOf(at, frameRate)
  var slot = outputSlotOf(startPosition, frameRate)
  while (true) {
    val pixels = next() ?: return null
    if (slot >= target) return PreviewFrame(outputSlotTime(slot, frameRate), pixels)
    slot++
  }
}

/**
 * Why this stream ran out of frames, once [PreviewStream.next] has returned null.
 *
 * A failed exit reads as [PreviewStream.exitError]. A clean exit, or a status that could not be read, says nothing more
 * than that the frames ran out.
 *
 * @return The failure, or null when the process did not report one.
 */
internal suspend fun PreviewStream.failedExit(): FilmstripError? =
  exitStatus()
    ?.takeIf {
      it != 0
    }?.let { exitError(it) }

/**
 * Reads a rendered frame back, out of the pump where it is already there and out of a process of
 * its own where it is not.
 *
 * The frame on screen is the frame the transport last presented, so a host drawing the readback at
 * the playhead is handed what it is already showing, with no spawn per tick. Anywhere
 * else is a short-lived process that renders the one frame and exits, which is what keeps this off
 * the transport: nothing about the playing process moves.
 *
 * @param scope The engine's dispatcher, which is where the transport state below is read.
 * @param planner Opens a pump against the plan the engine is holding.
 * @param plan What the engine last lowered, or null with nothing loaded.
 * @param composition The edit that plan came from.
 * @param presented The frame the transport last showed.
 * @param snap Rounds a requested position onto the composition's own frame grid.
 */
internal class FfmpegFrameReadback(
  private val scope: CoroutineScope,
  private val planner: FfmpegPreviewPlanner,
  private val plan: () -> PreviewPlan?,
  private val composition: () -> Composition?,
  private val presented: () -> PreviewFrame?,
  private val snap: (Duration) -> Duration,
) : PreviewFrameReadback {
  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    val job = scope.launch { callback.onReadback(render(position)) }
    return Cancellable { job.cancel() }
  }

  private suspend fun render(position: Duration): ReadbackResult {
    val current = plan() ?: return ReadbackResult.Failure(PlaybackError.SourceUnreadable(NOTHING_LOADED))
    val edit = composition() ?: return ReadbackResult.Failure(PlaybackError.SourceUnreadable(NOTHING_LOADED))
    val at = snap(position)

    presented()?.takeIf { it.answers(position, at, current.frameRate) }?.let { standing ->
      return ReadbackResult.Success(standing.toReadbackFrame(current))
    }

    val stream =
      when (val opened = planner.open(current, edit, at)) {
        is PreviewStreamResult.Refused -> return ReadbackResult.Failure(opened.error.toPlaybackError())
        is PreviewStreamResult.Opened -> opened.stream
      }

    try {
      val frame =
        stream.frameAt(at, current.frameRate)
          ?: return ReadbackResult.Failure(
            stream.failedExit()?.toPlaybackError() ?: PlaybackError.Underlying(NO_CODE, noFrame(at)),
          )
      return ReadbackResult.Success(frame.toReadbackFrame(current))
    } finally {
      stream.close()
    }
  }

  // A host's tick grid floors the playhead, so a position short of this frame and past the slot before it is this frame
  // seen through that grid. A position on any other slot's own time asks for that slot.
  private fun PreviewFrame.answers(
    requested: Duration,
    landing: Duration,
    frameRate: Int,
  ): Boolean {
    if (landing == position) return true
    val before = outputSlotTime(outputSlotOf(position, frameRate) - 1, frameRate)
    return requested > before && requested < position
  }

  private fun PreviewFrame.toReadbackFrame(plan: PreviewPlan): ReadbackFrame =
    ReadbackFrame(
      pixels = pixels,
      size = plan.resolved.output.size,
      presentationTime = position,
      colorSpace = if (plan.resolved.hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709,
      renderScale = plan.info.renderScale,
    )

  private companion object {
    const val NOTHING_LOADED = "No composition is loaded, so there is no frame to render."
    val NO_CODE = PlaybackError.Underlying.NO_PLATFORM_CODE

    fun noFrame(at: Duration): String = "The preview ran out of frames before $at."
  }
}
