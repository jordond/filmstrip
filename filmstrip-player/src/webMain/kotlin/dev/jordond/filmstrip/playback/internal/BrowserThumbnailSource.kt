package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.browserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.toBrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders strip frames through the compositor the encoder takes its frames from.
 *
 * The graph is the export's own, built by the same lowering the preview draws through, so a strip
 * frame is the frame the file carries. Each request builds a preview of its own, takes the one
 * frame and releases it, which is what keeps the decoders and the canvas a strip opens off the
 * preview that may be playing beside it.
 *
 * The compositor decodes from a sync sample up to the requested time, so every frame is the exact
 * one whether or not the request asked to be precise. There is no faster read to fall back to here.
 *
 * A `PlatformImage` costs its own pixels in the wasm heap for as long as it is open, so nothing
 * here holds a frame past handing it over: the compositor's copy is the image, and the preview it
 * came from is released before the callback runs.
 *
 * @param scope The page's dispatcher. A page has one thread, and the compositor and the decoders
 *   both live on it.
 * @param planner Lowers an edit the way an export of it would be lowered.
 */
@OptIn(InternalFilmstripApi::class)
internal class BrowserThumbnailSource(
  private val scope: CoroutineScope,
  private val planner: BrowserThumbnailPlanner,
) : ThumbnailSource {
  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    var delivered = false

    val job =
      scope.launch {
        val outcome = render(request)
        if (!delivered) {
          delivered = true
          callback.onThumbnail(outcome)
        }
      }

    // Cancelling unwinds the render inside the compositor pass, and the preview is released from a
    // finally on the way out, so the decoders it opened close rather than run the clip out.
    return Cancellable {
      delivered = true
      job.cancel()
    }
  }

  private suspend fun render(request: ThumbnailRequest): ThumbnailResult {
    val resolved =
      when (val plan = planner.lower(request)) {
        is BrowserThumbnailPlan.Refused -> return ThumbnailResult.Failure(plan.error)
        is BrowserThumbnailPlan.Ready -> plan.resolved
      }

    val preview = resolved.toBrowserPreview(request.composition)
    try {
      val frame =
        preview.frameAt(request.position)
          ?: return ThumbnailResult.Failure(
            ExportError.SourceUnreadable(request.composition.toString(), noFrame(request)),
          )

      return ThumbnailResult.Success(
        image = PlatformImage(frame.size.width, frame.size.height, frame.pixels),
        presentationTime = frame.presentationTime,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") broken: Throwable,
    ) {
      return ThumbnailResult.Failure(
        ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, broken.message ?: broken.toString()),
      )
    } finally {
      preview.release()
    }
  }

  private companion object {
    fun noFrame(request: ThumbnailRequest): String = "The composition has no frame at ${request.position}."
  }
}

/**
 * What lowering one thumbnail request settled on.
 */
internal sealed interface BrowserThumbnailPlan {
  /**
   * The edit lowered.
   */
  class Ready(
    val resolved: ResolvedComposition,
  ) : BrowserThumbnailPlan

  /**
   * The edit cannot be rendered here.
   */
  class Refused(
    val error: ExportError,
  ) : BrowserThumbnailPlan
}

/**
 * Lowers a thumbnail request through the same WebCodecs engine an export of it runs on.
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
internal class BrowserThumbnailPlanner(
  components: ComponentRegistry,
) {
  private val engine = browserExportEngine(components, chainedProber(components))

  suspend fun lower(request: ThumbnailRequest): BrowserThumbnailPlan {
    val natural =
      when (val result = engine.resolve(request.composition, ExportSpec())) {
        is ResolveResult.Refused -> return BrowserThumbnailPlan.Refused(result.error)
        is ResolveResult.Resolved -> result.composition
      }

    val naturalSize = natural.output.size
    val cap = request.heightPx.takeIf { it in 1..<naturalSize.height }
    if (cap == null) return BrowserThumbnailPlan.Ready(natural)

    return when (val result = engine.resolve(request.composition, ExportSpec(targetHeight = cap), naturalSize)) {
      is ResolveResult.Refused -> BrowserThumbnailPlan.Refused(result.error)
      is ResolveResult.Resolved -> BrowserThumbnailPlan.Ready(result.composition)
    }
  }
}
