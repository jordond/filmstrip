package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

// Turns the callback-shaped thumbnail SPI into the suspend and Flow forms the facade exposes.
@OptIn(InternalFilmstripApi::class)
internal class ThumbnailDispatcher(
  private val components: ComponentRegistry,
) {
  suspend fun frame(request: ThumbnailRequest): FrameResult = request(request).toFrameResult()

  fun frames(
    composition: EditComposition,
    at: List<Duration>,
    heightPx: Int,
  ): Flow<FrameResult> =
    flow {
      if (at.isEmpty()) return@flow

      // The whole run goes to one source in one call, which is what lets a backend hold a decoder
      // open across it. Frames are still produced one at a time: parallel extractions contend with
      // the preview's decoder for scarce hardware sessions and stutter playback.
      val effectsRevision = composition.effectsRevision()
      // A strip reads as a run of frames rather than as one exact instant, and a sync sample decodes
      // fast enough to fill one while the caller is still scrolling. presentationTime is what says
      // where each tile actually landed.
      val requests = at.map { ThumbnailRequest(composition, it, heightPx, effectsRevision, precise = false) }
      val source =
        components.thumbnailSourceFactories.firstNotNullOfOrNull { it.create(requests.first(), components) }
      if (source == null) {
        val failure = FrameResult.Failure(missing())
        repeat(requests.size) { emit(failure) }
        return@flow
      }

      // Buffered, so a source that reads ahead of the strip is not held to the slowest collector.
      // Anything still queued when this run ends is closed rather than left to the collector, which
      // by then has stopped taking frames.
      val arrivals = Channel<ThumbnailResult>(Channel.UNLIMITED, onUndeliveredElement = { it.dispose() })
      val handle =
        try {
          source.requestThumbnails(requests) { _, result ->
            if (arrivals.trySend(result).isFailure) result.dispose()
          }
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Throwable,
        ) {
          // A source that delivered some of the run and then threw leaves those frames queued with
          // nobody about to collect them, and the loop below never starts to close them. Cancelling
          // the channel runs the undelivered handler over everything already in it.
          arrivals.cancel()
          throw broken
        }

      try {
        repeat(requests.size) {
          val result = arrivals.receive().toFrameResult()
          try {
            emit(result)
          } catch (
            @Suppress("TooGenericExceptionCaught") broken: Throwable,
          ) {
            // Only a frame that reached nobody is this code's to close, and the collector's own
            // context is what says which happened. A cancelled collector never ran its body, which
            // is the ordinary case rather than a rare one: a strip scrolls past a position it
            // already asked for. A collector still active took the frame and stopped the flow of
            // its own accord, the way first() and take() do, so it is holding what it was handed.
            if (!currentCoroutineContext().isActive) (result as? FrameResult.Success)?.image?.close()
            throw broken
          }
        }
      } finally {
        handle.cancel()
        arrivals.cancel()
      }
    }

  private suspend fun request(request: ThumbnailRequest): ThumbnailResult {
    val source =
      components.thumbnailSourceFactories.firstNotNullOfOrNull { it.create(request, components) }
        ?: return ThumbnailResult.Failure(missing())

    return suspendCancellableCoroutine { continuation ->
      // Null until requestThumbnail has returned one, which is where a source that answers on the
      // calling thread lands. That case is released below instead.
      var issued: Cancellable? = null
      var delivered = false

      val handle =
        source.requestThumbnail(
          request,
        ) { result ->
          delivered = true
          // Released once the frame lands as well as on cancellation, which is what
          // [ThumbnailSource] promises a source: what one request held is freed through cancel()
          // however that request ended.
          issued?.cancel()
          // A frame resumed into a continuation that has since been cancelled never reaches the
          // caller, and nothing else is left holding it.
          continuation.resume(result) { _, dropped, _ ->
            (dropped as? ThumbnailResult.Success)?.image?.close()
          }
        }
      issued = handle

      if (delivered) handle.cancel()
      continuation.invokeOnCancellation { handle.cancel() }
    }
  }

  private fun ThumbnailResult.toFrameResult(): FrameResult =
    when (this) {
      is ThumbnailResult.Success -> {
        FrameResult.Success(
          image = image,
          presentationTime = presentationTime,
          colorSpace = ColorSpace.Bt709,
        )
      }
      is ThumbnailResult.Failure -> {
        FrameResult.Failure(error)
      }
    }

  private fun ThumbnailResult.dispose() {
    (this as? ThumbnailResult.Success)?.image?.close()
  }

  private fun missing(): ExportError.BackendMissing =
    ExportError.BackendMissing(
      artifact = "dev.jordond.filmstrip:filmstrip-player",
      message =
        "No thumbnail source claimed the request. Effect-applied frames need " +
          "dev.jordond.filmstrip:filmstrip-player, registered with " +
          "Filmstrip { playerBackend() }. " +
          "${components.thumbnailSourceFactories.size} factories were consulted.",
    )
}
