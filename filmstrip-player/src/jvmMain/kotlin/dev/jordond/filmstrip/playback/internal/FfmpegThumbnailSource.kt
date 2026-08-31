package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.ffmpeg.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
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
 * @param scope Where the pump runs. One serialised worker, since the dispatcher hands requests over
 *   one at a time and each of them waits on a pipe.
 * @param planner Lowers an edit the way an export of it would be lowered, and opens the pump on it.
 */
@OptIn(InternalFilmstripApi::class)
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
      when (val result = planner.lower(request)) {
        is FfmpegThumbnailPlan.Refused -> return ThumbnailResult.Failure(result.error)
        is FfmpegThumbnailPlan.Ready -> result
      }

    val stream =
      when (val opened = planner.open(plan, request.composition, request.position)) {
        is PreviewStreamResult.Refused -> return ThumbnailResult.Failure(opened.error)
        is PreviewStreamResult.Opened -> opened.stream
      }

    try {
      val frame =
        stream.frameAt(request.position, plan.resolved.frameStep)
          ?: return ThumbnailResult.Failure(
            ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, noFrame(request.position)),
          )

      return ThumbnailResult.Success(
        image = PlatformImage(frame.pixels.toBufferedImage(plan.resolved.output.size)),
        presentationTime = frame.position,
      )
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

  private companion object {
    fun noFrame(at: Duration): String = "The preview ran out of frames before $at."
  }
}

/**
 * What lowering one thumbnail request settled on.
 */
internal sealed interface FfmpegThumbnailPlan {
  /**
   * The edit lowered.
   *
   * @property resolved The graph a pump of this request runs.
   * @property spec What the pump lowers against, carrying the request's own height.
   * @property layoutSize The frame text lays out against, or null where nothing was capped.
   */
  class Ready(
    val resolved: ResolvedComposition,
    val spec: ExportSpec,
    val layoutSize: Size?,
  ) : FfmpegThumbnailPlan

  /**
   * The edit cannot be rendered here.
   */
  class Refused(
    val error: ExportError,
  ) : FfmpegThumbnailPlan
}

/**
 * Lowers a thumbnail request through the same ffmpeg engine an export of it runs on.
 *
 * The natural output frame is settled first and the request's height applied against it, so a
 * thumbnail smaller than the export renders at the height that was asked for. That lowering is
 * handed the natural frame as the one text lays out against, which keeps a caption breaking on the
 * same words in a strip frame as in the exported file.
 *
 * A height at or above the natural one lowers once and renders at the export's own frame, since
 * there is nothing to gain from rendering a strip larger than the file it came from.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
@OptIn(InternalFilmstripApi::class)
internal class FfmpegThumbnailPlanner(
  components: ComponentRegistry,
) {
  private val engine = components.ffmpegEngine()

  suspend fun lower(request: ThumbnailRequest): FfmpegThumbnailPlan {
    val natural =
      when (val result = engine.resolve(request.composition, ExportSpec())) {
        is ResolveResult.Refused -> return FfmpegThumbnailPlan.Refused(result.error)
        is ResolveResult.Resolved -> result.composition
      }

    val naturalSize = natural.output.size
    val cap = request.heightPx.takeIf { it in 1..<naturalSize.height }
    val spec = if (cap == null) ExportSpec() else ExportSpec(targetHeight = cap)
    val layoutSize = if (cap == null) null else naturalSize

    val resolved =
      when (cap) {
        null -> {
          natural
        }
        else -> {
          when (val result = engine.resolve(request.composition, spec, layoutSize)) {
            is ResolveResult.Refused -> return FfmpegThumbnailPlan.Refused(result.error)
            is ResolveResult.Resolved -> result.composition
          }
        }
      }

    return FfmpegThumbnailPlan.Ready(resolved, spec, layoutSize)
  }

  /**
   * Spawns the pump for one frame of [composition], windowed at [at].
   */
  suspend fun open(
    plan: FfmpegThumbnailPlan.Ready,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult = engine.openPreview(composition, plan.spec, plan.layoutSize, at)
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
