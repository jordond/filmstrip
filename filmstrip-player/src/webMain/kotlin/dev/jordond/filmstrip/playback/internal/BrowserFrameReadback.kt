package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Reads one rendered preview frame back out of the compositor the encoder takes its frames from.
 *
 * This is the whole display path on this backend: nothing is presented anywhere, and the Compose
 * surface draws these bytes itself. That makes the parity claim unusually direct, because the
 * pixels on screen are the pixels an export composites.
 *
 * A position the playback decoder is not already sitting on lands on a decoder of its own, so a
 * read-back away from the playhead neither uses nor throws away what playback is holding.
 *
 * @param scope The engine's dispatcher, which every callback is delivered on.
 * @param preview The graph the player is showing, or null before one is loaded.
 * @param renderScale The preview-only downscale in force.
 */
@OptIn(InternalFilmstripApi::class)
internal class BrowserFrameReadback(
  private val scope: CoroutineScope,
  private val preview: () -> BrowserPreview?,
  private val renderScale: () -> Float,
) : PreviewFrameReadback {
  override fun requestFrame(
    position: Duration,
    callback: ReadbackCallback,
  ): Cancellable {
    val target = preview() ?: return refuse(callback, NO_COMPOSITION)
    val scale = renderScale()
    var delivered = false

    val job =
      scope.launch {
        val outcome =
          try {
            when (val frame = target.frameAt(position)) {
              null -> {
                ReadbackResult.Failure(PlaybackError.SourceUnreadable(NO_FRAME))
              }
              else -> {
                ReadbackResult.Success(
                  ReadbackFrame(
                    pixels = frame.pixels,
                    size = frame.size,
                    presentationTime = frame.presentationTime,
                    // The canvas composites in standard range whatever the source carried, which is
                    // the same reason this backend leaves HdrPreview unclaimed.
                    colorSpace = ColorSpace.Bt709,
                    renderScale = scale,
                  ),
                )
              }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (
            @Suppress("TooGenericExceptionCaught") broken: Throwable,
          ) {
            ReadbackResult.Failure(
              PlaybackError.Underlying(
                PlaybackError.Underlying.NO_PLATFORM_CODE,
                broken.message ?: broken.toString(),
              ),
            )
          }

        if (!delivered) {
          delivered = true
          callback.onReadback(outcome)
        }
      }

    return Cancellable {
      delivered = true
      job.cancel()
    }
  }

  private fun refuse(
    callback: ReadbackCallback,
    message: String,
  ): Cancellable {
    callback.onReadback(ReadbackResult.Failure(PlaybackError.SourceUnreadable(message)))
    return Cancellable { }
  }

  private companion object {
    const val NO_COMPOSITION = "No composition is loaded, so there is no frame to read back."
    const val NO_FRAME = "The composition has no frame at that position."
  }
}
