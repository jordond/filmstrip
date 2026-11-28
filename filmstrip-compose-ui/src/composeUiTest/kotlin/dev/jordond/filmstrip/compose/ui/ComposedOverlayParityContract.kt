package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.overlay.rememberComposedOverlayState
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test

/**
 * The live element and the stage's own bake of it, side by side on two stages, reading the same pixels.
 *
 * Each platform's suite decodes the baked picture its own way.
 */
@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
abstract class ComposedOverlayParityContract {
  abstract fun decodePng(bytes: ByteArray): ImageBitmap

  @Test
  fun aTurnedElementLandsWhereItsBakeLands(): TestResult = parity(TURNED)

  @Test
  fun anElementOffTheEdgeIsClippedWhereItsBakeStops(): TestResult = parity(AT_EDGE)

  @Test
  fun anElementUnderALayerDrawnAtNoOpacityStillBakes(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState(CANVAS)
        setContent {
          val density = LocalDensity.current
          val box = with(density) { Modifier.size(BOX_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
          val stage = with(density) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
          Column {
            Spacer(Modifier.height(WINDOW_TOP))
            Stage(LIVE, box, stage, overlays) {
              ComposedOverlay(TURNED, onTransformChange = { }, modifier = Modifier.graphicsLayer { alpha = 0f }) {
                Element()
              }
            }
          }
        }
        awaitIdle()

        val overlay = pumped(async { overlays.bake(OUTPUT) }).single()

        val pixels = decodePng(overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes).toPixelMap()
        pixels[pixels.width / 2, pixels.height / 2].shouldBeNear(PLATE, "the baked plate's middle")
      }
    }

  private fun parity(transform: OverlayTransform): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState(CANVAS)
        var baked by mutableStateOf<Baked?>(null)
        var picture = Rect.Zero
        setContent {
          val density = LocalDensity.current
          val box = with(density) { Modifier.size(BOX_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
          val stage = with(density) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
          Column {
            Spacer(Modifier.height(WINDOW_TOP))
            Stage(LIVE, box, stage, overlays) {
              picture = frame.contentRect
              ComposedOverlay(transform, onTransformChange = { }) { Element() }
            }
            Stage(BAKED, box, stage) {
              baked?.let { bake ->
                Canvas(Modifier.matchParentSize()) {
                  val rect = frame.toView(bake.rect)
                  drawImage(
                    image = bake.bitmap,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                  )
                }
              }
            }
          }
        }
        awaitIdle()

        val overlay = pumped(async { overlays.bake(OUTPUT) }).single()
        val bitmap = decodePng(overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes)
        val placement = overlay.placedOn(OUTPUT, Size(bitmap.width, bitmap.height))
        baked = Baked(bitmap, placement.rectOn(OUTPUT))
        repeat(SETTLE_FRAMES) { pumpOnce() }

        val live = onNodeWithTag(LIVE).captureToImage().toPixelMap().fullSize(LIVE)
        val bake = onNodeWithTag(BAKED).captureToImage().toPixelMap().fullSize(BAKED)
        val points = Points(transform, picture)
        live.at(points.plate).shouldBeNear(PLATE, "the live plate's middle")
        live.at(points.marker).shouldBeNear(MARKER, "the live marker")
        live.at(points.outside).shouldBeNear(Color.Black, "outside the live plate")
        live.at(points.offFrame).shouldBeNear(Color.Black, "past the frame's edge, live")
        live.at(points.insideEnd).shouldBeNear(PLATE, "just inside the live plate's start")
        live.at(points.pastEnd).shouldBeNear(Color.Black, "just before the live plate's start")
        for ((name, point) in points.all) {
          withClue("$name, live ${live.at(point)} and baked ${bake.at(point)}") {
            live.at(point).distanceTo(bake.at(point)) shouldBeLessThan TOLERANCE
          }
        }
      }
    }

  @Composable
  private fun Stage(
    tag: String,
    box: Modifier,
    stage: Modifier,
    overlays: ComposedOverlayState = rememberComposedOverlayState(),
    content: @Composable VideoStageScope.() -> Unit,
  ) {
    Box(box.testTag(tag).background(Color.Black)) {
      VideoStage(
        player = null,
        outputAspect = OUTPUT.width.toFloat() / OUTPUT.height,
        modifier = stage,
        overlays = overlays,
      ) {
        content()
      }
    }
  }

  // Frames only come when the test clock moves, and the encode runs on a real thread, so this steps both.
  private suspend fun <T> ComposeUiTest.pumped(work: Deferred<T>): T {
    while (!work.isCompleted) pumpOnce()
    return work.await()
  }

  private suspend fun ComposeUiTest.pumpOnce() {
    mainClock.advanceTimeByFrame()
    awaitIdle()
    withContext(Dispatchers.Default) { delay(1) }
  }

  // A node running past the window's edge is captured short, and its rows no longer line up with the other stage's.
  private fun PixelMap.fullSize(tag: String): PixelMap =
    apply {
      withClue("the $tag stage's capture, a node past the window's edge is captured short") {
        IntSize(width, height) shouldBe IntSize(BOX_WIDTH, STAGE_HEIGHT)
      }
    }

  // Where each sampled point lands on a stage showing its picture at [picture].
  private class Points(
    transform: OverlayTransform,
    picture: Rect,
  ) {
    private val scale = transform.drawnScale(CANVAS, picture.width)
    private val radians = transform.rotationDegrees * PI / DEGREES_HALF_TURN
    private val centre =
      Offset(picture.left + transform.center.x * picture.width, picture.top + transform.center.y * picture.height)

    val plate = centre
    val marker = centre + turned(Offset(MARKER_FROM_CENTRE_PX * scale, 0f))
    val outside = centre + turned(Offset(0f, (PLATE_HEIGHT_PX / 2f + MARGIN_PX) * scale))

    // Either side of the plate's start, a few stage pixels clear of its antialiased edge, which a scale or shift of a
    // pixel or two moves across. The start stays on the frame in both cases.
    val insideEnd = centre + turned(Offset(-(PLATE_WIDTH_PX / 2f * scale - EDGE_PX), 0f))
    val pastEnd = centre + turned(Offset(-(PLATE_WIDTH_PX / 2f * scale + EDGE_PX), 0f))
    val offFrame = Offset(picture.right + MARGIN_PX, centre.y)
    val all =
      listOf(
        "plate" to plate,
        "marker" to marker,
        "outside" to outside,
        "off the frame" to offFrame,
        "inside the start" to insideEnd,
        "before the start" to pastEnd,
      )

    private fun turned(offset: Offset): Offset {
      val cos = cos(radians).toFloat()
      val sin = sin(radians).toFloat()
      return Offset(offset.x * cos - offset.y * sin, offset.x * sin + offset.y * cos)
    }
  }

  private class Baked(
    val bitmap: ImageBitmap,
    val rect: dev.jordond.filmstrip.geometry.NormalizedRect,
  )

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val TURNED = OverlayTransform(Anchor(0.45f, 0.5f), scale = 1.2f, rotationDegrees = 30f)
    val AT_EDGE = OverlayTransform(Anchor(0.95f, 0.5f), scale = 1.2f, rotationDegrees = 15f)
    val PLATE = Color(0xFFE04020)
    val MARKER = Color(0xFF2060E0)

    // The box is wider than the stage inside it, so a strip past the frame's right edge is the box's own black. Both
    // stages fit the 320 by 640 screen of CI's emulator.
    const val STAGE_WIDTH = 256
    const val STAGE_HEIGHT = 144
    const val BOX_WIDTH = 288

    // The plate is 200 by 60 dp and the marker 40 dp square, 10 dp in from its start, at the layout density of 3.
    const val PLATE_WIDTH_PX = 600f
    const val PLATE_HEIGHT_PX = 180f
    const val EDGE_PX = 2.5f
    const val MARKER_FROM_CENTRE_PX = -210f
    const val MARGIN_PX = 6f

    // The action bar covers the top of a test window on edge to edge levels, 275 px on a 2.625 density phone, so the
    // stages start below it in dp.
    val WINDOW_TOP = 110.dp

    const val LIVE = "live"
    const val BAKED = "baked"
    const val SETTLE_FRAMES = 3
    const val DEGREES_HALF_TURN = 180.0
    const val TOLERANCE = 2f / 255f
  }
}

@Composable
private fun Element() {
  Box(Modifier.size(200.dp, 60.dp).background(Color(0xFFE04020))) {
    Box(
      Modifier
        .align(Alignment.CenterStart)
        .offset(x = 10.dp)
        .size(40.dp)
        .background(Color(0xFF2060E0)),
    )
  }
}

private fun PixelMap.at(point: Offset): Color {
  val x = point.x.roundToInt()
  val y = point.y.roundToInt()
  check(x in 0 until width && y in 0 until height) { "$point is off the ${width}x$height capture." }
  return this[x, y]
}

private fun Color.distanceTo(other: Color): Float =
  maxOf(abs(red - other.red), abs(green - other.green), abs(blue - other.blue), abs(alpha - other.alpha))

private fun Color.shouldBeNear(
  expected: Color,
  name: String,
) {
  withClue("$name read $this") { (distanceTo(expected) < NEAR) shouldBe true }
}

// Room for the sRGB round trip a capture and a screen read each take.
private const val NEAR = 3f / 255f
