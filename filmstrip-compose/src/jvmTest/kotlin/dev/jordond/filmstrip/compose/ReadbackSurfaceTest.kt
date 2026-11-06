package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.ReadbackResult
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * Every surface here is drawn into a 100x100 box over black, on a density of one, so a pixel read off the capture is a
 * pixel of the box.
 */
@OptIn(ExperimentalTestApi::class)
class ReadbackSurfaceTest {
  @Test
  fun `the frame is drawn into the rectangle the output aspect fits`() =
    runComposeUiTest {
      val readback = ScriptedReadback { ReadbackResult.Success(solidFrame(Size(4, 2), Color.Red)) }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()

      val pixels = capture()
      val rect = videoRect(WIDE, BOX, VideoContentScale.Fit)
      val top = ((BOX.height - rect.height) / 2f).roundToInt()
      val bottom = top + rect.height.roundToInt()

      pixels[50, 50] shouldBe Color.Red
      pixels[50, top + EDGE_MARGIN] shouldBe Color.Red
      pixels[50, bottom - 1 - EDGE_MARGIN] shouldBe Color.Red
      pixels[50, top - 1 - EDGE_MARGIN] shouldBe Color.Black
      pixels[50, bottom + EDGE_MARGIN] shouldBe Color.Black
    }

  @Test
  fun `a crop fills the box the fit would have letterboxed`() =
    runComposeUiTest {
      val readback = ScriptedReadback { ReadbackResult.Success(solidFrame(Size(4, 2), Color.Red)) }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Crop)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()

      val pixels = capture()

      pixels[50, 50] shouldBe Color.Red
      pixels[50, 5] shouldBe Color.Red
      pixels[50, 94] shouldBe Color.Red
    }

  @Test
  fun `nothing is drawn while the output size is still unknown`() =
    runComposeUiTest {
      val readback = ScriptedReadback { ReadbackResult.Success(solidFrame(Size(4, 2), Color.Red)) }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      waitForIdle()

      readback.requested.shouldNotBeEmpty()
      capture()[50, 50] shouldBe Color.Black
    }

  @Test
  fun `a failed readback leaves the surface empty`() =
    runComposeUiTest {
      val readback = ScriptedReadback { ReadbackResult.Failure(PlaybackError.SourceUnreadable("nothing to read")) }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()

      readback.requested.shouldNotBeEmpty()
      capture()[50, 50] shouldBe Color.Black
    }

  @Test
  fun `a moved playhead asks for the frame there and draws it in place of the last one`() =
    runComposeUiTest {
      val readback =
        ScriptedReadback { position ->
          val color = if (position == Duration.ZERO) Color.Red else Color.Blue
          ReadbackResult.Success(solidFrame(Size(4, 2), color))
        }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()
      capture()[50, 50] shouldBe Color.Red

      engine.reportPosition(ON_TICK)
      waitForIdle()

      readback.requested.last() shouldBe ON_TICK
      capture()[50, 50] shouldBe Color.Blue
    }

  @Test
  fun `a surface shown before the load finishes draws the first frame once it does`() =
    runComposeUiTest {
      var loaded = false
      val readback =
        ScriptedReadback {
          if (loaded) {
            ReadbackResult.Success(solidFrame(Size(4, 2), Color.Red))
          } else {
            ReadbackResult.Failure(PlaybackError.SourceUnreadable("nothing loaded yet"))
          }
        }
      val engine = RecordingEngine(readback, initialState = RecordingEngine.PREPARING)
      showSurface(engine, VideoContentScale.Fit)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()
      capture()[50, 50] shouldBe Color.Black

      loaded = true
      engine.reportState(RecordingEngine.READY)
      waitForIdle()

      capture()[50, 50] shouldBe Color.Red
    }

  @Test
  fun `a rebuild holding the playhead keeps the last frame until it can draw its own`() =
    runComposeUiTest {
      var color = Color.Red
      val readback = ScriptedReadback { ReadbackResult.Success(solidFrame(Size(4, 2), color)) }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      engine.reportPreviewInfo(previewInfo(WIDE))
      waitForIdle()
      capture()[50, 50] shouldBe Color.Red

      color = Color.Blue
      engine.reportState(RecordingEngine.PREPARING)
      waitForIdle()
      capture()[50, 50] shouldBe Color.Red

      engine.reportState(RecordingEngine.READY)
      waitForIdle()
      capture()[50, 50] shouldBe Color.Blue
    }

  @Test
  fun `a readback still in flight is cancelled once the playhead moves past it`() =
    runComposeUiTest {
      val readback = ScriptedReadback { null }
      val engine = RecordingEngine(readback)
      showSurface(engine, VideoContentScale.Fit)
      waitForIdle()

      engine.reportPosition(ON_TICK)
      waitForIdle()

      readback.requested shouldBe listOf(Duration.ZERO, ON_TICK)
      readback.cancelled shouldBe listOf(Duration.ZERO)
    }

  private fun ComposeUiTest.showSurface(
    engine: RecordingEngine,
    contentScale: VideoContentScale,
  ) {
    setContent {
      val player = rememberFilmstripPlayer(filmstripWith(engine), testComposition("surface.mp4"))
      SurfaceBox { ReadbackSurface(player, Modifier.fillMaxSize(), contentScale) }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.capture(): PixelMap = onRoot().captureToImage().toPixelMap()

  private companion object {
    val WIDE = Size(200, 100)
    val BOX = ComposeSize(100f, 100f)

    // A whole number of 33ms ticks, so the position the surface asks for is the one reported.
    val ON_TICK = 990.milliseconds

    // Clear of the one pixel either side of an edge that filtering may blend.
    const val EDGE_MARGIN = 2
  }
}
