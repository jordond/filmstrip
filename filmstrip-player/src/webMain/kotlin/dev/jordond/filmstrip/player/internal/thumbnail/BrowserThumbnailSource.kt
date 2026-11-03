package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine
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
internal class BrowserThumbnailSource(
  private val scope: CoroutineScope,
  private val planner: BrowserThumbnailPlanner,
) : ThumbnailSource {
  /**
   * How many previews a render has open right now.
   *
   * A request opens one once its edit is lowered and releases it before its callback runs, whether it finished or was
   * cancelled.
   */
  var openPreviews: Int = 0
    private set

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
      when (val plan = lowerThumbnail { planner.lower(request) }) {
        is PlanResult.Refused -> return ThumbnailResult.Failure(plan.error)
        is PlanResult.Ready -> plan.plan.resolved
      }

    val preview = resolved.toBrowserPreview(request.composition)
    openPreviews++
    try {
      val frame =
        preview.frameAt(request.position)
          ?: return ThumbnailResult.Failure(
            FilmstripError.SourceUnreadable(request.composition.toString(), noFrame(request)),
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
        FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, broken.message ?: broken.toString()),
      )
    } finally {
      preview.release()
      openPreviews--
    }
  }

  private companion object {
    fun noFrame(request: ThumbnailRequest): String = "The composition has no frame at ${request.position}."
  }
}
