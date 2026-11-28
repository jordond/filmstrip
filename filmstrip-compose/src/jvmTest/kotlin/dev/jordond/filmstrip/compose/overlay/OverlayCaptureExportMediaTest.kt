package dev.jordond.filmstrip.compose.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * An element a stage recorded, planned with its overlays and exported through ffmpeg, lands on the pixels its bounds
 * name.
 */
@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class OverlayCaptureExportMediaTest {
  @Test
  fun aTurnedElementExportsWherePlacedOnPutsIt(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState(CANVAS)
        setContent {
          TestStage(overlays) {
            TestElement(overlays, OUTPUT, TURNED) { Box(Modifier.size(200.dp, 60.dp).background(Color(COLOR))) }
          }
        }
        awaitIdle()
        val filmstrip = Filmstrip { ffmpegBackend() }
        val edit =
          compositionOf {
            solid(GREY, 1.seconds)
            frame(OutputFrame.Fixed(OUTPUT))
          }
        val work = async { filmstrip.planWithOverlays(edit, ExportSpec(), overlays) }
        while (!work.isCompleted) {
          mainClock.advanceTimeByFrame()
          awaitIdle()
          withContext(Dispatchers.Default) { delay(1) }
        }
        val plan = work.await().shouldBeInstanceOf<Verdict.Capable>().plan
        val overlay =
          plan.composition.effects
            .single()
            .shouldBeInstanceOf<ImageOverlay>()
        val output = File.createTempFile("filmstrip-capture", ".mp4").also { it.delete() }

        withContext(Dispatchers.Default) {
          val statuses = filmstrip.export(plan, MediaSink.of(output.path)).toList()
          statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
        }

        val frame = firstFrameOf(output)
        val bounds = TURNED.boundsOn(PLATE_PX, CANVAS, OUTPUT).shouldNotBeNull()
        val raster = Size(bounds.raster.width, bounds.raster.height)
        val rect = overlay.placedOn(OUTPUT, raster).rectOn(OUTPUT)
        val middleX = ((rect.left + rect.right) / 2 * OUTPUT.width).roundToInt()
        val middleY = ((rect.top + rect.bottom) / 2 * OUTPUT.height).roundToInt()
        assertReads(frame, middleX, middleY, COLOR, "the plate's middle")
        assertReads(
          frame,
          bounds.raster.left + CORNER_INSET,
          bounds.raster.top + CORNER_INSET,
          GREY,
          "inside the raster outside the turned plate",
        )
        output.delete()
      }
    }

  private fun firstFrameOf(file: File): ByteArray {
    val ffmpeg = checkNotNull(System.getProperty("filmstrip.ffmpeg")) { "filmstrip.ffmpeg is not set." }
    val process =
      ProcessBuilder(
        ffmpeg,
        "-v",
        "error",
        "-i",
        file.path,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-",
      ).redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val bytes = process.inputStream.readBytes()
    process.waitFor()
    withClue("one rgb24 frame of $OUTPUT") { bytes.size shouldBe OUTPUT.width * OUTPUT.height * RGB }
    return bytes
  }

  private fun assertReads(
    frame: ByteArray,
    x: Int,
    y: Int,
    argb: Int,
    what: String,
  ) {
    val read =
      (0 until RGB).map { channel ->
        val samples =
          (-PATCH..PATCH).flatMap { dy ->
            (-PATCH..PATCH).map { dx -> frame[((y + dy) * OUTPUT.width + x + dx) * RGB + channel].toInt() and 0xFF }
          }
        samples.average().roundToInt()
      }
    val expected = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    withClue("$what at $x,$y read $read, expected $expected") {
      read.zip(expected).all { (got, want) -> abs(got - want) <= DRIFT } shouldBe true
    }
  }

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val TURNED = OverlayTransform(Anchor(0.3f, 0.6f), rotationDegrees = 30f)
    const val COLOR = 0xFFE67814.toInt()
    const val GREY = 0xFF808080.toInt()

    // 200 by 60 dp at the layout density of 3.
    val PLATE_PX = IntSize(600, 180)
    const val RGB = 3
    const val PATCH = 2
    const val CORNER_INSET = 6

    // How far a channel may read off its colour after a 4:2:0 encode. The plate and the solid sit about 100 codes
    // apart on red and blue.
    const val DRIFT = 12
  }
}
