package dev.jordond.filmstrip.compose.ui.geometry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.jordond.filmstrip.compose.ui.interaction.CropHandle
import dev.jordond.filmstrip.geometry.NormalizedRect
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CropFrameTest {
  @Test
  fun `a rect at the edges of the frame maps to the edges of the content rect`() {
    val frame = CropFrame(Rect(100f, 50f, 900f, 450f))

    frame.toView(NormalizedRect.Full) shouldBe Rect(100f, 50f, 900f, 450f)
  }

  @Test
  fun `a rect in the middle of the frame is scaled and offset by the content rect`() {
    val frame = CropFrame(Rect(100f, 50f, 900f, 450f))

    frame.toView(NormalizedRect(0.25f, 0.5f, 0.75f, 1f)) shouldBe Rect(300f, 250f, 700f, 450f)
  }

  @Test
  fun `toNormalized is the inverse of toView`() {
    val frame = CropFrame(Rect(100f, 50f, 900f, 450f))
    val rect = NormalizedRect(0.2f, 0.3f, 0.6f, 0.9f)

    frame.toNormalized(frame.toView(rect)) shouldBe rect
  }

  @Test
  fun `a degenerate content rect maps back to the full frame rather than dividing by zero`() {
    val frame = CropFrame(Rect(100f, 50f, 100f, 450f))

    frame.toNormalized(Rect(150f, 100f, 200f, 200f)) shouldBe NormalizedRect.Full
  }

  @Test
  fun `handleAt prefers a corner over an edge when both are in range`() {
    val frame = CropFrame(Rect(0f, 0f, 800f, 400f))
    val rect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

    // Right on the top-left corner, which is also well inside touch range of the top and left
    // edges. The corner must still win.
    frame.handleAt(Offset(200f, 100f), rect, touchRadiusPx = 40f) shouldBe CropHandle.TopLeft
  }

  @Test
  fun `handleAt finds an edge away from any corner`() {
    val frame = CropFrame(Rect(0f, 0f, 800f, 400f))
    val rect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

    // The middle of the top edge, far from either corner.
    frame.handleAt(Offset(400f, 100f), rect, touchRadiusPx = 20f) shouldBe CropHandle.Top
  }

  @Test
  fun `handleAt answers Body for a position inside the rect away from every edge`() {
    val frame = CropFrame(Rect(0f, 0f, 800f, 400f))
    val rect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

    frame.handleAt(Offset(400f, 200f), rect, touchRadiusPx = 20f) shouldBe CropHandle.Body
  }

  @Test
  fun `handleAt answers null outside the rect and its handles`() {
    val frame = CropFrame(Rect(0f, 0f, 800f, 400f))
    val rect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

    frame.handleAt(Offset(10f, 10f), rect, touchRadiusPx = 20f) shouldBe null
  }

  @Test
  fun `handleAt reads a letterboxed content rect with a non-zero offset`() {
    // A 16:9 video fit into an 800x800 box: bars left and right, content offset from zero.
    val frame = CropFrame(Rect(100f, 0f, 700f, 800f))
    val rect = NormalizedRect(0.5f, 0.5f, 1f, 1f)

    // The bottom-right corner of that rect, in view pixels: right is content left(100) plus the
    // full 600px content width, and bottom is the full 800px content height.
    frame.handleAt(Offset(700f, 800f), rect, touchRadiusPx = 15f) shouldBe CropHandle.BottomRight
  }
}
