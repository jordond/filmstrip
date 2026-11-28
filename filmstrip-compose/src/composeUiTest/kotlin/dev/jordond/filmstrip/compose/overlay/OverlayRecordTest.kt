package dev.jordond.filmstrip.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.math.roundToInt
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class OverlayRecordTest {
  @Test
  fun aNodeThatRecordsAndDrawsNothingStillRecords(): TestResult =
    runComposeUiTest {
      val stage = Stage()
      val hidden = Recording("hidden")
      setContent { stage.Content { stage.Record(hidden, draws = false) { Plate(Color.Red) } } }
      awaitIdle()

      stage.redraw(this, hidden)

      hidden.size shouldBe PLATE_PX
      stage.bake(hidden).shouldShowThePlate()
    }

  @Test
  fun aNodePlacedOutsideItsParentsClipStillRecords(): TestResult =
    runComposeUiTest {
      val stage = Stage()
      val off = Recording("off")
      setContent {
        stage.Content {
          Box(Modifier.size(40.dp).clipToBounds()) {
            stage.Record(off, Modifier.offset { IntOffset(FAR_PX, FAR_PX) }) { Plate(Color.Red) }
          }
        }
      }
      awaitIdle()

      stage.redraw(this, off)

      off.size shouldBe PLATE_PX
      stage.bake(off).shouldShowThePlate()
    }

  @Test
  fun siblingsRecordInTheOrderTheyAreDrawn(): TestResult =
    runComposeUiTest {
      val stage = Stage()
      val bottom = Recording("bottom")
      val raised = Recording("raised")
      val top = Recording("top")
      setContent {
        stage.Content {
          Box {
            stage.Record(bottom) { Plate(Color.Red) }
            stage.Record(raised, Modifier.zIndex(1f)) { Plate(Color.Green) }
            stage.Record(top, draws = false) { Plate(Color.Blue) }
          }
        }
      }
      awaitIdle()

      stage.redraw(this, bottom, raised, top)

      listOf(bottom, raised, top).sortedBy { it.stamp }.map { it.name } shouldContainExactly
        listOf("bottom", "top", "raised")
    }

  @Test
  fun siblingsOnTheirOwnLayersRecordInTheOrderTheyAreDrawn(): TestResult =
    runComposeUiTest {
      val stage = Stage()
      val bottom = Recording("bottom")
      val raised = Recording("raised")
      val top = Recording("top")
      setContent {
        stage.Content {
          Box {
            stage.Record(bottom, Modifier.graphicsLayer { rotationZ = TURN }) { Plate(Color.Red) }
            stage.Record(raised, Modifier.zIndex(1f).graphicsLayer { rotationZ = TURN }) { Plate(Color.Green) }
            stage.Record(top, Modifier.graphicsLayer { rotationZ = TURN }, draws = false) { Plate(Color.Blue) }
          }
        }
      }
      awaitIdle()

      stage.redraw(this, bottom, raised, top)

      listOf(bottom, raised, top).sortedBy { it.stamp }.map { it.name } shouldContainExactly
        listOf("bottom", "top", "raised")
    }

  @Test
  fun aReplayOfAStageRecordingEqualsTheHostsCapture(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val stage = Stage()
        val capturer = OverlayCapturer()
        val element = Recording("element")
        setContent {
          OverlayCaptureHost(capturer)
          stage.Content { stage.Record(element) { Plate(HALF_RED) } }
        }
        awaitIdle()

        stage.redraw(this@runComposeUiTest, element)
        val replayed = stage.bake(element).shouldNotBeNull()
        val captured =
          pumped(async { capturer.captureRaster(MID, CANVAS, OUTPUT, {}) { Plate(HALF_RED) } }).shouldNotBeNull()

        replayed.bounds shouldBe captured.bounds
        replayed.bitmap.pixels() shouldBe captured.bitmap.pixels()
      }
    }

  private fun Captured?.shouldShowThePlate() {
    val captured = shouldNotBeNull()
    captured.bounds shouldBe MID.boundsOn(PLATE_PX, CANVAS, OUTPUT)
    val raster = captured.bounds.raster
    val pixels = captured.bitmap.toPixelMap()
    val middle =
      pixels[
        (MID.center.x * OUTPUT.width).roundToInt() - raster.left,
        (MID.center.y * OUTPUT.height).roundToInt() - raster.top,
      ]
    withClue("the plate's middle read $middle") {
      (middle.alpha > 1f - STEP && middle.red > 1f - STEP) shouldBe true
      middle.green shouldBe 0f
    }
    withClue("a corner outside the turned plate") { pixels[0, 0].alpha shouldBe 0f }
  }

  private fun ImageBitmap.pixels(): List<Int> {
    val buffer = IntArray(width * height)
    readPixels(buffer)
    return listOf(width, height) + buffer.asList()
  }

  // Stands in for a stage: lays each element out the way the capture host does and records what it draws.
  private class Stage {
    private val redraw = mutableIntStateOf(0)
    private var stamps = 0
    private var graphics: GraphicsContext? = null

    @Composable
    fun Content(content: @Composable () -> Unit) {
      graphics = LocalGraphicsContext.current
      CompositionLocalProvider(LocalDensity provides OverlayLayoutDensity, content = content)
    }

    @Composable
    fun Record(
      recording: Recording,
      modifier: Modifier = Modifier,
      draws: Boolean = true,
      content: @Composable () -> Unit,
    ) {
      val layer = rememberGraphicsLayer()
      val constraints = CANVAS.constraintsFor(OUTPUT)
      Layout(
        content = {
          Box(
            Modifier.drawWithContent {
              val asked = redraw.intValue
              layer.record { this@drawWithContent.drawContent() }
              recording.layer = layer
              recording.asked = asked
              if (draws) drawLayer(layer)
            },
          ) { content() }
        },
        // The stamp sits outside any layer the element is placed with, so it runs when the parent draws the element
        // and not when the element's own layer is recorded again.
        modifier =
          Modifier
            .drawWithContent {
              redraw.intValue
              recording.stamp = ++stamps
              drawContent()
            }.then(modifier),
      ) { list, _ ->
        val placeable = list.single().measure(constraints)
        recording.size = IntSize(placeable.width, placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }
    }

    // Asks every node for one more draw and steps frames until each has recorded after the request.
    suspend fun redraw(
      test: ComposeUiTest,
      vararg recordings: Recording,
    ) {
      val asked = ++redraw.intValue
      var frames = 0
      while (recordings.any { it.asked < asked } && frames++ < REDRAW_FRAMES) test.pumpOnce()
      recordings.forEach { withClue("${it.name} recorded after the redraw") { (it.asked >= asked) shouldBe true } }
    }

    suspend fun bake(recording: Recording): Captured? =
      bakeRecorded(recording.layer.shouldNotBeNull(), recording.size, MID, CANVAS, OUTPUT, graphics.shouldNotBeNull())
  }

  private class Recording(
    val name: String,
  ) {
    var layer: GraphicsLayer? = null
    var size: IntSize = IntSize.Zero
    var asked: Int = -1
    var stamp: Int = 0
  }

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val MID = OverlayTransform(Anchor(0.31f, 0.62f), scale = 1.37f, rotationDegrees = 23f)
    val HALF_RED = Color(0x80FF0000)
    val PLATE_PX = IntSize(600, 180)
    const val FAR_PX = 5000
    const val TURN = 10f

    // One eight-bit step, for a premultiplied raster read back straight.
    const val STEP = 1.5f / 255f
    const val REDRAW_FRAMES = 60
  }
}
