package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class CornerBracketsTest {
  private val bitmap = bracketsBitmap()

  @Test
  fun `each corner draws an arm along its edge and one down its side`() {
    bitmap.pixel(BOX.left.toInt() + 5, BOX.top.toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.left.toInt(), BOX.top.toInt() + 5) shouldBe Color.Red
    bitmap.pixel(BOX.right.toInt() - 5, BOX.top.toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.right.toInt(), BOX.top.toInt() + 5) shouldBe Color.Red
    bitmap.pixel(BOX.right.toInt() - 5, BOX.bottom.toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.right.toInt(), BOX.bottom.toInt() - 5) shouldBe Color.Red
    bitmap.pixel(BOX.left.toInt() + 5, BOX.bottom.toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.left.toInt(), BOX.bottom.toInt() - 5) shouldBe Color.Red
  }

  // Halfway along an arm is drawn and past its length is not, which an arm running the whole edge cannot pass.
  @Test
  fun `an arm runs inward for its length and stops`() {
    bitmap.pixel(BOX.left.toInt() + (LENGTH / 2).toInt(), BOX.top.toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.left.toInt() + LENGTH.toInt() + 5, BOX.top.toInt()) shouldBe Color.Black
    bitmap.pixel(BOX.right.toInt(), BOX.bottom.toInt() - (LENGTH / 2).toInt()) shouldBe Color.Red
    bitmap.pixel(BOX.right.toInt(), BOX.bottom.toInt() - LENGTH.toInt() - 5) shouldBe Color.Black
  }

  @Test
  fun `nothing is drawn inside the box or outside its corners`() {
    bitmap.pixel(BOX.center.x.toInt(), BOX.center.y.toInt()) shouldBe Color.Black
    bitmap.pixel(BOX.left.toInt() - 5, BOX.top.toInt() - 5) shouldBe Color.Black
  }

  private fun bracketsBitmap(): Bitmap {
    val image =
      renderComposeScene(width = WIDTH_PX, height = HEIGHT_PX) {
        Canvas(Modifier.size(width = WIDTH_PX.dp, height = HEIGHT_PX.dp).background(Color.Black)) {
          drawCornerBrackets(BOX, LENGTH, Color.Red, STROKE)
        }
      }

    return Bitmap().apply {
      allocN32Pixels(WIDTH_PX, HEIGHT_PX)
      image.readPixels(this)
    }
  }

  private fun Bitmap.pixel(
    x: Int,
    y: Int,
  ): Color = Color(getColor(x, y))

  private companion object {
    const val WIDTH_PX = 120
    const val HEIGHT_PX = 100
    const val LENGTH = 20f
    const val STROKE = 2f
    val BOX = Rect(left = 10f, top = 10f, right = 100f, bottom = 80f)
  }
}
