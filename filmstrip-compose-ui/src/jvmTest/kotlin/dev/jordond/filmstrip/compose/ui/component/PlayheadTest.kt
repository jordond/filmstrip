package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
class PlayheadTest {
  @Test
  fun `an offset moves where the line is drawn, not just what the position reads`() {
    val scale = TimelineScale(DURATION, PIXELS_PER_SECOND)
    val colors = FilmstripTimelineDefaults.colors(playhead = Color.Red)

    // 1s of player time at a 2s offset draws where 3s of timeline time would, which a formula
    // that forgot the offset cannot tell apart from drawing at 1s.
    val bitmap = playheadBitmap(scale, position = 1.seconds, sourceOffset = 2.seconds, colors = colors)

    bitmap.pixel(300, ROW) shouldBe Color.Red
    bitmap.pixel(100, ROW) shouldBe Color.Black
  }

  private fun playheadBitmap(
    scale: TimelineScale,
    position: Duration,
    sourceOffset: Duration,
    colors: TimelineColors,
  ): Bitmap {
    val image =
      renderComposeScene(width = WIDTH_PX, height = HEIGHT_PX) {
        Box(Modifier.size(width = WIDTH_PX.dp, height = HEIGHT_PX.dp).background(Color.Black)) {
          Playhead(
            position = { position },
            scale = scale,
            sourceOffset = { sourceOffset },
            colors = colors,
          )
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
    val DURATION = 30.seconds
    const val PIXELS_PER_SECOND = 100f
    const val WIDTH_PX = 400
    const val HEIGHT_PX = 48
    const val ROW = 40
  }
}
