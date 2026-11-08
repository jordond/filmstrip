package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How a shared placement rounds to the whole pixels ffmpeg and media3 both draw.
 */
class WholePixelsTest {
  // Four by five into 1366 by 768 puts both sides between whole pixels, 375.8 and 990.2 across, so each edge lands on
  // the pixel edge nearest it. This pins the pixels ffmpeg's scale and pad and media3's placement pass both draw.
  @Test
  fun `rounds each edge to the nearest whole pixel edge`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Contain)
    val pixels = rect.inWholePixelsOf(ODD_FRAME)

    pixels shouldBe PixelRect(376, 0, 990, 768)
    pixels.width shouldBe 614
    pixels.height shouldBe 768
    pixels.left.toFloat() shouldBe (rect.left * ODD_FRAME.width plusOrMinus 0.5f)
    pixels.right.toFloat() shouldBe (rect.right * ODD_FRAME.width plusOrMinus 0.5f)
  }

  @Test
  fun `keeps a rect under a pixel one pixel wide and tall`() {
    val pixels = NormalizedRect(0.5f, 0.5f, 0.5001f, 0.5001f).inWholePixelsOf(Size(100, 100))

    pixels.width shouldBe 1
    pixels.height shouldBe 1
  }

  @Test
  fun `keeps the edges of a rect that runs past the frame`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Crop)
    val pixels = rect.inWholePixelsOf(ODD_FRAME)

    pixels.top.toFloat() shouldBe (rect.top * ODD_FRAME.height plusOrMinus 0.5f)
    pixels.bottom.toFloat() shouldBe (rect.bottom * ODD_FRAME.height plusOrMinus 0.5f)
    pixels.top shouldBeLessThan 0
  }

  private companion object {
    val PORTRAIT = Size(1080, 1350)
    val ODD_FRAME = Size(1366, 768)
  }
}
