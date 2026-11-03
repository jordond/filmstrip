package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every ruler here is drawn 400 pixels wide over black, on a density of one, with red ticks and labels that draw
 * nothing. At 20 pixels a second the default 56 pixel spacing settles on five second ticks, 100 pixels apart.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TimelineRulerTest {
  @Test
  fun `ticks sit a chosen interval apart from the start up to the edge of the ruler`() {
    val scale = TimelineScale(60.seconds, PIXELS_PER_SECOND)

    val ruler = drawRuler(scale)

    ruler.intervals shouldBe setOf(scale.tickInterval(FilmstripTimelineDefaults.MinTickSpacing.value))
    ruler.labels shouldBe listOf(0.seconds, 5.seconds, 10.seconds, 15.seconds, 20.seconds)
    for (x in listOf(100, 200, 300)) ruler.isTickAt(x) shouldBe true
    ruler.isTickAt(150) shouldBe false
  }

  @Test
  fun `a scrolled ruler starts at the tick the scroll has reached and draws it shifted back`() {
    val scale = TimelineScale(60.seconds, PIXELS_PER_SECOND)

    // 250 pixels in is half way through the third interval, so the 10s tick sits 50 pixels off screen.
    val ruler = drawRuler(scale, scrollPx = 250f)

    ruler.labels shouldBe listOf(10.seconds, 15.seconds, 20.seconds, 25.seconds, 30.seconds)
    for (x in listOf(50, 150, 250, 350)) ruler.isTickAt(x) shouldBe true
    ruler.isTickAt(100) shouldBe false
    ruler.isTickAt(200) shouldBe false
  }

  @Test
  fun `no tick is drawn past the end of the timeline`() {
    val scale = TimelineScale(12.seconds, PIXELS_PER_SECOND)

    val ruler = drawRuler(scale)

    ruler.labels shouldBe listOf(0.seconds, 5.seconds, 10.seconds)
    ruler.isTickAt(200) shouldBe true
    ruler.isTickAt(300) shouldBe false
  }

  @Test
  fun `a wider minimum spacing steps up to a coarser interval`() {
    val scale = TimelineScale(60.seconds, PIXELS_PER_SECOND)

    val ruler = drawRuler(scale, minTickSpacing = 120.dp)

    ruler.intervals shouldBe setOf(scale.tickInterval(120f))
    ruler.labels shouldBe listOf(0.seconds, 10.seconds, 20.seconds)
    ruler.isTickAt(200) shouldBe true
    ruler.isTickAt(100) shouldBe false
  }

  @Test
  fun `a timeline drawn at no width at all has no ticks`() {
    val ruler = drawRuler(TimelineScale(60.seconds, pixelsPerSecond = 0f))

    ruler.labels.shouldBeEmpty()
    ruler.isTickAt(0) shouldBe false
  }

  private fun drawRuler(
    scale: TimelineScale,
    scrollPx: Float = 0f,
    minTickSpacing: Dp = FilmstripTimelineDefaults.MinTickSpacing,
  ): DrawnRuler {
    val labels = mutableListOf<Duration>()
    val intervals = mutableSetOf<Duration>()
    val colors = FilmstripTimelineDefaults.colors(ruler = Color.Red, rulerLabel = Color.Transparent)

    val image =
      renderComposeScene(width = WIDTH_PX, height = HEIGHT_PX) {
        Box(Modifier.size(width = WIDTH_PX.dp, height = HEIGHT_PX.dp).background(Color.Black)) {
          TimelineRuler(
            scale = scale,
            scrollPx = { scrollPx },
            height = HEIGHT_PX.dp,
            colors = colors,
            minTickSpacing = minTickSpacing,
            label = { time, interval ->
              labels += time
              intervals += interval
              ""
            },
          )
        }
      }

    val bitmap =
      Bitmap().apply {
        allocN32Pixels(WIDTH_PX, HEIGHT_PX)
        image.readPixels(this)
      }
    // A label is asked for once per tick on every draw, so the ticks of one draw are the distinct ones.
    return DrawnRuler(bitmap, labels.distinct(), intervals)
  }

  private class DrawnRuler(
    private val bitmap: Bitmap,
    val labels: List<Duration>,
    val intervals: Set<Duration>,
  ) {
    // A hairline on a whole pixel boundary is blended across the two columns either side of it.
    fun isTickAt(x: Int): Boolean =
      listOf(x - 1, x).filter { it in 0 until WIDTH_PX }.any { Color(bitmap.getColor(it, TICK_ROW)) != Color.Black }
  }

  private companion object {
    const val WIDTH_PX = 400
    const val HEIGHT_PX = 28
    const val PIXELS_PER_SECOND = 20f

    // Near the bottom, inside the tick and below where any label would sit.
    const val TICK_ROW = 26
  }
}
