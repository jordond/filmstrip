package dev.jordond.filmstrip.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class SerialThumbnailRunJvmTest {
  @Test
  fun `a frame landing after the run was cancelled is closed and never delivered`() {
    var answer: (() -> Unit)? = null
    val image = PlatformImage(BufferedImage(16, 9, BufferedImage.TYPE_INT_ARGB))
    val source =
      ThumbnailSource { request, callback ->
        answer = { callback.onThumbnail(ThumbnailResult.Success(image, request.position)) }
        Cancellable { }
      }
    val delivered = mutableListOf<Int>()

    val requests = listOf(requestAt(0), requestAt(40))

    val run = SerialThumbnailRun(source::requestThumbnail, requests) { index, _ -> delivered += index }
    run.cancel()
    answer?.invoke()

    delivered shouldBe emptyList()
    image.widthPx shouldBe 0
  }

  @Test
  fun `a frame landing before cancellation belongs to the callback and stays open`() {
    val image = PlatformImage(BufferedImage(16, 9, BufferedImage.TYPE_INT_ARGB))
    val source =
      ThumbnailSource { request, callback ->
        callback.onThumbnail(ThumbnailResult.Success(image, request.position))
        Cancellable { }
      }
    val delivered = mutableListOf<Int>()

    SerialThumbnailRun(source::requestThumbnail, listOf(requestAt(0))) { index, _ -> delivered += index }.cancel()

    delivered shouldBe listOf(0)
    image.widthPx shouldBe 16
  }

  private fun requestAt(millis: Int): ThumbnailRequest =
    ThumbnailRequest(COMPOSITION, millis.milliseconds, 90, 0L, precise = false)

  private companion object {
    val COMPOSITION = compositionOf { clip(MediaSource.of("/fixtures/strip.mp4")) }
  }
}
