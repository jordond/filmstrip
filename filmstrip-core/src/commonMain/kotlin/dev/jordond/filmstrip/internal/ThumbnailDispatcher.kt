package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

// Turns the callback-shaped thumbnail SPI into the suspend and Flow forms the facade exposes.
internal class ThumbnailDispatcher(
  private val components: ComponentRegistry,
) {
  suspend fun frame(request: ThumbnailRequest): FrameResult =
    when (val result = request(request)) {
      is ThumbnailResult.Success -> {
        FrameResult.Success(
          image = result.image,
          presentationTime = result.presentationTime,
          colorSpace = ColorSpace.Bt709,
        )
      }
      is ThumbnailResult.Failure -> {
        FrameResult.Failure(result.error)
      }
    }

  fun frames(
    composition: EditComposition,
    at: List<Duration>,
    heightPx: Int,
  ): Flow<FrameResult> =
    flow {
      // Serialised on purpose: parallel extractions contend with the preview's decoder for scarce
      // hardware sessions and stutter playback.
      at.forEach { position ->
        emit(frame(ThumbnailRequest(composition, position, heightPx, effectsRevision = 0L)))
      }
    }

  private suspend fun request(request: ThumbnailRequest): ThumbnailResult {
    val source =
      components.thumbnailSourceFactories.firstNotNullOfOrNull { it.create(request, components) }
        ?: return ThumbnailResult.Failure(missing())

    return suspendCancellableCoroutine { continuation ->
      val handle =
        source.requestThumbnail(
          request,
        ) { result -> continuation.resume(result) }
      continuation.invokeOnCancellation { handle.cancel() }
    }
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
