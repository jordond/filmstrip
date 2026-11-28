package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class OverlayCaptureTest {
  @Test
  fun aTurnedPlateIsDrawnIntoTheRasterItsBoundsName(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = hosted()

        val captured = pumped(async { capturer.captureRaster(TURNED, CANVAS, OUTPUT, {}) { Plate(HALF_RED) } })

        val raster = captured.shouldNotBeNull().bounds.raster
        val expected = TURNED.boundsOn(PLATE_PX, CANVAS, OUTPUT).shouldNotBeNull()
        captured.bounds shouldBe expected
        captured.bitmap.width shouldBe raster.width
        captured.bitmap.height shouldBe raster.height
        val pixels = captured.bitmap.toPixelMap()
        val middle = pixels[plateMiddleX(raster.left), plateMiddleY(raster.top)]
        withClue("the plate's middle read $middle") {
          (abs(middle.alpha - HALF_RED.alpha) <= STEP) shouldBe true
          (abs(middle.red - 1f) <= STEP) shouldBe true
          middle.green shouldBe 0f
        }
        withClue("a corner outside the turned plate") { pixels[0, 0].alpha shouldBe 0f }
      }
    }

  @Test
  fun aCaptureWaitsForReadyBeforeItRecords(): TestResult =
    runComposeUiTest {
      coroutineScope {
        var loaded by mutableStateOf(false)
        val capturer = hosted()

        val work =
          async {
            capturer.captureRaster(TURNED, CANVAS, OUTPUT, { snapshotFlow { loaded }.first { it } }) {
              Plate(if (loaded) Color.Blue else Color.Red)
            }
          }
        val captured = pumped(work) { frame -> if (frame == LATE_FRAMES) loaded = true }.shouldNotBeNull()

        val raster = captured.bounds.raster
        val middle = captured.bitmap.toPixelMap()[plateMiddleX(raster.left), plateMiddleY(raster.top)]
        withClue("the plate's middle read $middle") { (middle.blue > 1f - STEP && middle.red < STEP) shouldBe true }
      }
    }

  @Test
  fun aCaptureWithNoHostIsRefused(): TestResult =
    runComposeUiTest {
      coroutineScope {
        setContent { }
        val failure = runCatching { OverlayCapturer().capture(TURNED, CANVAS, OUTPUT) { Plate(HALF_RED) } }

        failure.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe NO_HOST
      }
    }

  @Test
  fun aCaptureFromABackgroundThreadGivesAnOverlayAtTheRasterCentre(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = hosted()

        val work =
          async {
            withContext(
              Dispatchers.Default,
            ) { capturer.capture(TURNED, CANVAS, OUTPUT, opacity = 0.5f) { Plate(Color.Red) } }
          }
        val overlay = pumped(work).shouldNotBeNull()

        val bounds = TURNED.boundsOn(PLATE_PX, CANVAS, OUTPUT).shouldNotBeNull()
        overlay.position shouldBe OverlayPosition.At(bounds.center)
        overlay.scale shouldBe bounds.raster.width.toFloat() / OUTPUT.width
        overlay.opacity shouldBe 0.5f
        val png = overlay.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes
        png.copyOfRange(0, PNG_SIGNATURE.size).toList() shouldBe PNG_SIGNATURE.toList()
        Size(png.bigEndianAt(IHDR_WIDTH), png.bigEndianAt(IHDR_HEIGHT)) shouldBe
          Size(bounds.raster.width, bounds.raster.height)
      }
    }

  @Test
  fun anElementWhollyOffTheFrameGivesNoOverlay(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = hosted()

        val off = OverlayTransform(Anchor(3f, 0.5f))
        pumped(async { capturer.capture(off, CANVAS, OUTPUT) { Plate(Color.Red) } }).shouldBeNull()
      }
    }

  @Test
  fun aCaptureQueuedWhenTheHostLeavesIsRefusedRatherThanLeftWaiting(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = OverlayCapturer()
        var mounted by mutableStateOf(true)
        setContent { if (mounted) OverlayCaptureHost(capturer) }
        awaitIdle()

        val held = CompletableDeferred<Unit>()
        val first =
          async {
            runCatching {
              capturer.captureRaster(
                TURNED,
                CANVAS,
                OUTPUT,
                { held.await() },
              ) { Plate(Color.Red) }
            }
          }
        val queued = async { runCatching { capturer.captureRaster(TURNED, CANVAS, OUTPUT, {}) { Plate(Color.Red) } } }
        repeat(LATE_FRAMES) { pumpOnce() }
        mounted = false
        repeat(LATE_FRAMES) { pumpOnce() }

        withClue("the capture in hand") { first.isCompleted shouldBe true }
        withClue("the capture queued behind it") { queued.isCompleted shouldBe true }
        first.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
        queued.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
      }
    }

  @Test
  fun aFailureInReadyReachesTheCallerAndTheNextCaptureStillRuns(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = hosted()

        val failed =
          pumped(
            async {
              runCatching {
                capturer.captureRaster(
                  TURNED,
                  CANVAS,
                  OUTPUT,
                  { error(READY_FAILED) },
                ) { Plate(Color.Red) }
              }
            },
          )
        val next = pumped(async { capturer.captureRaster(TURNED, CANVAS, OUTPUT, {}) { Plate(Color.Red) } })

        failed.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe READY_FAILED
        next.shouldNotBeNull()
      }
    }

  @Test
  fun aCancelledCaptureLeavesTheHostFreeForTheNext(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val capturer = hosted()

        val held = CompletableDeferred<Unit>()
        val cancelled = async { capturer.captureRaster(TURNED, CANVAS, OUTPUT, { held.await() }) { Plate(Color.Red) } }
        repeat(LATE_FRAMES) { pumpOnce() }
        cancelled.cancel()
        val next = pumped(async { capturer.captureRaster(TURNED, CANVAS, OUTPUT, {}) { Plate(Color.Red) } })

        cancelled.isCancelled shouldBe true
        next.shouldNotBeNull()
      }
    }

  private suspend fun ComposeUiTest.hosted(): OverlayCapturer {
    val capturer = OverlayCapturer()
    setContent { OverlayCaptureHost(capturer) }
    awaitIdle()
    return capturer
  }

  // Where the plate's centre lands inside a raster that starts at [left] and [top].
  private fun plateMiddleX(left: Int): Int = (TURNED.center.x * OUTPUT.width).roundToInt() - left

  private fun plateMiddleY(top: Int): Int = (TURNED.center.y * OUTPUT.height).roundToInt() - top

  private fun ByteArray.bigEndianAt(offset: Int): Int =
    (0 until Int.SIZE_BYTES).fold(
      0,
    ) { value, index -> (value shl BYTE_BITS) or (this[offset + index].toInt() and 0xFF) }

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val TURNED = OverlayTransform(Anchor(0.3f, 0.6f), scale = 1.5f, rotationDegrees = 30f)
    val HALF_RED = Color(0x80FF0000)

    // 200 by 60 dp at the layout density of 3.
    val PLATE_PX = IntSize(600, 180)
    const val LATE_FRAMES = 6
    const val READY_FAILED = "the font did not load"

    // One eight-bit step, for a premultiplied raster read back straight.
    const val STEP = 1.5f / 255f
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    const val IHDR_WIDTH = 16
    const val IHDR_HEIGHT = 20
    const val BYTE_BITS = 8
  }
}
