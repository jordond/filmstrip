package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import dev.jordond.filmstrip.media.PlatformImage
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import kotlin.test.Test

class PlatformImageBitmapTest {
  @Test
  fun `every pixel lands where it was in the image`() {
    // Three wide, so a row stride that was off by one pixel would shift the middle column.
    val image =
      PlatformImage(
        BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
          setRGB(0, 0, RED)
          setRGB(1, 0, GREEN)
          setRGB(2, 0, BLUE)
          setRGB(0, 1, WHITE)
          setRGB(1, 1, BLACK)
          setRGB(2, 1, YELLOW)
        },
      )

    val bitmap = image.toImageBitmap().shouldNotBeNull()

    bitmap.width shouldBe 3
    bitmap.height shouldBe 2
    val pixels = bitmap.toPixelMap()
    pixels[0, 0] shouldBe Color.Red
    pixels[1, 0] shouldBe Color.Green
    pixels[2, 0] shouldBe Color.Blue
    pixels[0, 1] shouldBe Color.White
    pixels[1, 1] shouldBe Color.Black
    pixels[2, 1] shouldBe Color.Yellow
  }

  @Test
  fun `a translucent pixel keeps its color rather than being darkened by its alpha`() {
    val image =
      PlatformImage(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, HALF_RED) },
      )

    val pixel = image.toImageBitmap().shouldNotBeNull().toPixelMap()[0, 0]

    pixel.alpha shouldBe (128 / 255f plusOrMinus TOLERANCE)
    pixel.red shouldBe (1f plusOrMinus TOLERANCE)
    pixel.green shouldBe 0f
  }

  @Test
  fun `a closed image has nothing to convert`() {
    val image = PlatformImage(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB))
    image.close()

    image.toImageBitmap().shouldBeNull()
  }

  private companion object {
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val HALF_RED = 0x80FF0000.toInt()

    // One 8-bit step, for a premultiplied read back to straight alpha.
    const val TOLERANCE = 1 / 255f
  }
}
