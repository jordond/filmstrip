package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.ffmpeg.PreviewStreamResult
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
 * Reads a rendered frame back, out of the pump where it is already there and out of a process of
 * its own where it is not.
 *
 * The frame on screen is the frame the transport last presented, so a host drawing the readback at
 * the playhead is handed what it is already showing rather than costing a spawn per tick. Anywhere
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
@OptIn(InternalFilmstripApi::class)
internal class FfmpegFrameReadback(
  private val scope: CoroutineScope,
  private val planner: FfmpegPreviewPlanner,
  private val plan: () -> FfmpegPreviewPlan?,
  private val composition: () -> EditComposition?,
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
    val step = current.frameStep

    // Within a frame of what is on screen, because the position a host draws against has been
    // through its own tick grid on the way here and lands beside the frame rather than on it.
    presented()?.takeIf { (it.position - at) in -step..step }?.let { standing ->
      return ReadbackResult.Success(standing.toReadbackFrame(current))
    }

    val stream =
      when (val opened = planner.open(current, edit, at)) {
        is PreviewStreamResult.Refused -> return ReadbackResult.Failure(opened.error.toPlaybackError())
        is PreviewStreamResult.Opened -> opened.stream
      }

    try {
      val frame =
        stream.frameAt(at, step) ?: return ReadbackResult.Failure(PlaybackError.Underlying(NO_CODE, noFrame(at)))
      return ReadbackResult.Success(frame.toReadbackFrame(current))
    } finally {
      stream.close()
    }
  }

  /**
   * Reads forward to [at], for a composition whose timeline the input seek could not window.
   *
   * A windowed stream opens on the frame asked for, so this takes its first frame and stops.
   */
  private suspend fun PreviewStream.frameAt(
    at: Duration,
    step: Duration,
  ): PreviewFrame? {
    var position = startPosition
    while (true) {
      val pixels = next() ?: return null
      if (position >= at - step / 2) return PreviewFrame(position, pixels)
      position += step
    }
  }

  private fun PreviewFrame.toReadbackFrame(plan: FfmpegPreviewPlan): ReadbackFrame =
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
