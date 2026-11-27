package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.compose.overlay.OverlayCaptureHost
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.rememberOverlayCapturer
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The drawing as the export bakes it: one raster the size of the frame, clear where the eraser went.
 */
@OptIn(ExperimentalTestApi::class)
class StoryDrawingCaptureTest {
  @Test
  fun aStrokeCrossedByAnEraserBakesClearOnTheCrossingAndPaintedOffIt(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = host()
        val strokes =
          listOf(
            StoryStroke(listOf(Anchor(0.2f, 0.5f), Anchor(0.8f, 0.5f)), StoryBrush.Pen, RED, WIDTH_DP),
            StoryStroke(listOf(Anchor(0.5f, 0.3f), Anchor(0.5f, 0.7f)), StoryBrush.Eraser, RED, WIDTH_DP),
          )

        val work = async { bakeDrawing(capturer, strokes, StoryEditor().canvas, OUTPUT) }
        while (!work.isCompleted) {
          mainClock.advanceTimeByFrame()
          awaitIdle()
          withContext(Dispatchers.Default) { delay(1) }
        }

        val overlay = work.await().shouldNotBeNull()
        val picture = ImageIO.read(ByteArrayInputStream(overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes))
        picture.width shouldBe OUTPUT.width
        picture.height shouldBe OUTPUT.height
        alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 2)) shouldBe 0
        picture.getRGB(OUTPUT.width * 3 / 10, OUTPUT.height / 2) shouldBe RED
        alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 10)) shouldBe 0
      }
    }

  @Test
  fun aMarkerLeavesThePictureShowingThroughIt(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = host()
        val strokes = listOf(StoryStroke(listOf(Anchor(0.2f, 0.5f), Anchor(0.8f, 0.5f)), StoryBrush.Marker, RED))

        val work = async { bakeDrawing(capturer, strokes, StoryEditor().canvas, OUTPUT) }
        while (!work.isCompleted) {
          mainClock.advanceTimeByFrame()
          awaitIdle()
          withContext(Dispatchers.Default) { delay(1) }
        }

        val overlay: ImageOverlay = work.await().shouldNotBeNull()
        val picture = ImageIO.read(ByteArrayInputStream(overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes))
        val alpha = alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 2))
        alpha shouldBeGreaterThan 0
        (OPAQUE - alpha) shouldBeGreaterThan 0
      }
    }

  @Test
  fun anEmptyDrawingBakesToNoOverlay(): TestResult =
    runComposeUiTest {
      val capturer = host()

      bakeDrawing(capturer, emptyList(), StoryEditor().canvas, OUTPUT).shouldBeNull()
    }

  private suspend fun ComposeUiTest.host(): OverlayCapturer {
    lateinit var capturer: OverlayCapturer
    setContent {
      capturer = rememberOverlayCapturer()
      OverlayCaptureHost(capturer)
    }
    awaitIdle()
    return capturer
  }

  private fun alphaAt(argb: Int): Int = argb ushr ALPHA_SHIFT

  private companion object {
    val OUTPUT = Size(360, 640)
    const val RED = 0xFFFF0000.toInt()
    const val WIDTH_DP = 12f
    const val ALPHA_SHIFT = 24
    const val OPAQUE = 255
  }
}
