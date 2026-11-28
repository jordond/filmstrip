package dev.jordond.filmstrip.compose.overlay

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt
import kotlin.test.Test

@OptIn(InternalFilmstripApi::class)
class OverlayCanvasTest {
  @Test
  fun aFrameMeasuresTheSameByItsSizeAndByItsAspect() {
    for (canvas in CANVASES) {
      for (frame in FRAMES) {
        withClue("$frame on $canvas") {
          val width = canvas.widthPx()
          // The height a frame's size gave before the aspect form existed, which neither form may move.
          val height = (width.toFloat() * frame.height / frame.width).roundToInt()
          val expected = Constraints(maxWidth = width, maxHeight = height)

          canvas.constraintsFor(frame) shouldBe expected
          canvas.constraintsFor(frame.width.toFloat() / frame.height) shouldBe expected
        }
      }
    }
  }

  @Test
  fun aFrameWithNoWidthMeasuresSquare() {
    val canvas = OverlayCanvas(360.dp)

    canvas.constraintsFor(Size(0, 480)) shouldBe Constraints(maxWidth = 1080, maxHeight = 1080)
    canvas.constraintsFor(0f) shouldBe Constraints(maxWidth = 1080, maxHeight = 1080)
  }

  private companion object {
    val CANVASES = listOf(OverlayCanvas(360.dp), OverlayCanvas(320.dp), OverlayCanvas(411.dp))
    val FRAMES = listOf(Size(1080, 1920), Size(1920, 1080), Size(1280, 720), Size(853, 480), Size(1179, 2556))
  }
}
