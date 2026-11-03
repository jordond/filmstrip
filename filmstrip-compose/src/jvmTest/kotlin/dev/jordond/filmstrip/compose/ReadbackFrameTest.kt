package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ReadbackFrameTest {
  @Test
  fun `the packed bytes are read row by row in rgba order`() {
    // Three wide, so a row stride that was off by one pixel would shift the middle column.
    val frame =
      readbackFrame(
        Size(3, 2),
        listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black, Color.Yellow),
      )

    val bitmap = frame.toImageBitmap().shouldNotBeNull()

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
  fun `the bitmap is a copy, so the frame's buffer can be reused once it is converted`() {
    val frame = readbackFrame(Size(1, 1), listOf(Color.Red))
    val bitmap = frame.toImageBitmap().shouldNotBeNull()

    frame.pixels.fill(0)

    bitmap.toPixelMap()[0, 0] shouldBe Color.Red
  }

  @Test
  fun `a frame with no width or no height has nothing to convert`() {
    readbackFrame(Size(0, 4), emptyList()).toImageBitmap().shouldBeNull()
    readbackFrame(Size(4, 0), emptyList()).toImageBitmap().shouldBeNull()
    readbackFrame(Size(-1, 4), emptyList()).toImageBitmap().shouldBeNull()
  }
}
