package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.ReadbackResult
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The desktop and browser surfaces, which both draw the readback rather than a native view.
 */
@OptIn(ExperimentalTestApi::class)
class VideoSurfaceTest {
  @Test
  fun `the surface draws the frame the readback produced`() =
    runComposeUiTest {
      val readback = ScriptedReadback { ReadbackResult.Success(solidFrame(Size(4, 2), Color.Red)) }
      val engine = RecordingEngine(readback)

      setContent {
        val player = rememberFilmstripPlayer(filmstripWith(engine), testComposition("surface.mp4"))
        SurfaceBox { VideoSurface(player, Modifier.fillMaxSize()) }
      }
      waitForIdle()
      engine.reportPreviewInfo(previewInfo(Size(200, 100)))
      waitForIdle()

      readback.requested.shouldNotBeEmpty()
      val pixels = onRoot().captureToImage().toPixelMap()
      // The default fit letterboxes a two to one frame into the middle half of the square box.
      pixels[50, 50] shouldBe Color.Red
      pixels[50, 10] shouldBe Color.Black
    }
}
