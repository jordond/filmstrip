package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.frameAt
import dev.jordond.filmstrip.player.internal.frameRate
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Renders strip frames on a pump of their own, one process per frame.
 *
 * The process runs the graph an export of the same edit would run, out of the same lowering the
 * preview opens its pump from, so a strip frame is the frame the file carries. It renders the one
 * frame that was asked for and exits, which is what keeps a strip filling in the background off a
 * preview that may be playing beside it.
 *
 * The pump reads forward from its seek to the frame covering the requested time, so every frame is
 * the exact one whether or not the request asked to be precise. There is no faster read to fall
 * back to here.
 *
 * @param scope Where the pump runs. One serialised worker, since the dispatcher hands requests over
 *   one at a time and each of them waits on a pipe.
 * @param planner Lowers an edit the way an export of it would be lowered, and opens the pump on it.
 */
internal class FfmpegThumbnailSource(
  private val scope: CoroutineScope,
  private val planner: FfmpegThumbnailPlanner,
) : ThumbnailSource {
  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    // Atomic because the two sides run on different threads: the render is confined to the source's
    // own scope and a caller cancels from wherever it happens to be.
    val delivered = AtomicBoolean(false)

    val job =
      scope.launch {
        val outcome = render(request)
        if (delivered.compareAndSet(false, true)) {
          callback.onThumbnail(outcome)
        } else {
          // Cancelled while the frame was being rendered, so it reaches nobody and this is the last
          // code that can still close it.
          (outcome as? ThumbnailResult.Success)?.image?.close()
        }
      }

    // Cancelling the job unwinds the render, and the stream is closed from a finally on the way
    // out, so the process is killed rather than left writing into a pipe nobody reads.
    return Cancellable {
      delivered.set(true)
      job.cancel()
    }
  }

  private suspend fun render(request: ThumbnailRequest): ThumbnailResult {
    val plan =
      when (val result = lowerThumbnail { planner.lower(request) }) {
        is PlanResult.Refused -> return ThumbnailResult.Failure(result.error)
        is PlanResult.Ready -> result.plan
      }

    val stream =
      when (val opened = planner.open(plan, request.composition, request.position)) {
        is PreviewStreamResult.Refused -> return ThumbnailResult.Failure(opened.error)
        is PreviewStreamResult.Opened -> opened.stream
      }

    try {
      val frame =
        stream.frameAt(request.position, plan.resolved.frameRate)
          ?: return ThumbnailResult.Failure(
            FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, noFrame(request.position)),
          )

      return ThumbnailResult.Success(
        image = PlatformImage(frame.pixels.toBufferedImage(plan.resolved.output.size)),
        presentationTime = frame.position,
      )
    } finally {
      stream.close()
    }
  }

  private companion object {
    fun noFrame(at: Duration): String = "The preview ran out of frames before $at."
  }
}

/**
 * These packed RGBA pixels as the image the JVM form of a frame wraps.
 *
 * Every pixel is opaque, the frame having been flattened onto the composition's fill inside the
 * graph, so the alpha channel is written full.
 */
private fun ByteArray.toBufferedImage(size: Size): BufferedImage {
  val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
  val argb = IntArray(size.width * size.height)

  for (pixel in argb.indices) {
    val base = pixel * CHANNELS
    argb[pixel] =
      OPAQUE_ALPHA or
      ((this[base].toInt() and BYTE_MASK) shl RED_SHIFT) or
      ((this[base + 1].toInt() and BYTE_MASK) shl GREEN_SHIFT) or
      (this[base + 2].toInt() and BYTE_MASK)
  }

  image.setRGB(0, 0, size.width, size.height, argb, 0, size.width)
  return image
}

private const val CHANNELS = 4
private const val BYTE_MASK = 0xFF
private const val OPAQUE_ALPHA = 0xFF shl 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
