package dev.jordond.filmstrip.sample.stories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.sample.stories.ui.StoryStage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The drawing as the stage bakes it: one raster the size of the frame, clear where the eraser went.
 */
@OptIn(ExperimentalTestApi::class)
class StoryDrawingCaptureTest {
  @Test
  fun aStrokeCrossedByAnEraserBakesClearOnTheCrossingAndPaintedOffIt(): TestResult =
    runComposeUiTest {
      val editor = StoryEditor()
      editor.story.addStroke(StoryStroke(listOf(Anchor(0.2f, 0.5f), Anchor(0.8f, 0.5f)), StoryBrush.Pen, RED, WIDTH_DP))
      editor.story.addStroke(
        StoryStroke(listOf(Anchor(0.5f, 0.3f), Anchor(0.5f, 0.7f)), StoryBrush.Eraser, RED, WIDTH_DP),
      )

      val picture = pictureOf(bake(editor).single())

      picture.width shouldBe OUTPUT.width
      picture.height shouldBe OUTPUT.height
      alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 2)) shouldBe 0
      picture.getRGB(OUTPUT.width * 3 / 10, OUTPUT.height / 2) shouldBe RED
      alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 10)) shouldBe 0
    }

  @Test
  fun aMarkerLeavesThePictureShowingThroughIt(): TestResult =
    runComposeUiTest {
      val editor = StoryEditor()
      editor.story.addStroke(StoryStroke(listOf(Anchor(0.2f, 0.5f), Anchor(0.8f, 0.5f)), StoryBrush.Marker, RED))

      val picture = pictureOf(bake(editor).single())

      val alpha = alphaAt(picture.getRGB(OUTPUT.width / 2, OUTPUT.height / 2))
      alpha shouldBeGreaterThan 0
      (OPAQUE - alpha) shouldBeGreaterThan 0
    }

  @Test
  fun anEmptyDrawingBakesToNoOverlay(): TestResult =
    runComposeUiTest {
      bake(StoryEditor()).shouldBeEmpty()
    }

  private suspend fun ComposeUiTest.bake(editor: StoryEditor): List<ImageOverlay> =
    coroutineScope {
      setContent {
        val size = with(LocalDensity.current) { Modifier.size(OUTPUT.width.toDp(), OUTPUT.height.toDp()) }
        Box(size) { StoryStage(editor, player = null) }
      }
      awaitIdle()
      val work = async { editor.overlays.bake(OUTPUT) }
      while (!work.isCompleted) {
        mainClock.advanceTimeByFrame()
        awaitIdle()
        withContext(Dispatchers.Default) { delay(1) }
      }
      work.await()
    }

  private fun pictureOf(overlay: ImageOverlay): BufferedImage =
    ImageIO.read(ByteArrayInputStream(overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes))

  private fun alphaAt(argb: Int): Int = argb ushr ALPHA_SHIFT

  private companion object {
    val OUTPUT = Size(360, 640)
    const val RED = 0xFFFF0000.toInt()
    const val WIDTH_DP = 12f
    const val ALPHA_SHIFT = 24
    const val OPAQUE = 255
  }
}
