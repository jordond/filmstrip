package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.support.assertReads
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AuthoredColors
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * An image overlay placed at a point lands its centre there in the file ffmpeg writes.
 */
class FfmpegOverlayPointMediaTest {
  private val fixture = FfmpegExportFixture()
  private val directory: File = Files.createTempDirectory("filmstrip-overlay-point").toFile()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `a picture at a point covers the rectangle placedOn gives on a landscape frame`() = landsWherePlaced(LANDSCAPE)

  @Test
  fun `a picture at a point covers the rectangle placedOn gives on a portrait frame`() = landsWherePlaced(PORTRAIT)

  private fun landsWherePlaced(frame: Size) =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val picture = File(directory, "square.png").apply { writeBytes(pngOf(OVERLAY_COLOR, OVERLAY_SIZE)) }
      val overlay = ImageOverlay(ImageSource.of(picture.path), OverlayPosition.At(POINT), scale = SCALE)
      val composition =
        compositionOf {
          solid(AuthoredColors.MID_GREY, 1.seconds)
          frame(OutputFrame.Fixed(frame))
          effects { add(overlay) }
        }
      val output = File(directory, "out.mp4")

      val plan = fixture.plannedRun(composition, ExportSpec(frameRate = RATE))
      fixture.exported(plan, output)

      val size = plan.output.size
      val rect = overlay.placedOn(size, OVERLAY_SIZE).rectOn(size)
      val left = rect.left * size.width
      val right = rect.right * size.width
      val top = rect.top * size.height
      val bottom = rect.bottom * size.height
      val middleX = (left + right) / 2
      val middleY = (top + bottom) / 2
      val inside =
        mapOf(
          "the centre" to (middleX to middleY),
          "inside the start edge" to (left + EDGE to middleY),
          "inside the end edge" to (right - EDGE to middleY),
          "inside the top edge" to (middleX to top + EDGE),
          "inside the bottom edge" to (middleX to bottom - EDGE),
        )
      val outside =
        mapOf(
          "past the start edge" to (left - EDGE to middleY),
          "past the end edge" to (right + EDGE to middleY),
          "above the top edge" to (middleX to top - EDGE),
          "below the bottom edge" to (middleX to bottom + EDGE),
        )
      inside.forEach { (what, point) ->
        assertReads(writtenFrames(output.path, probe = blockAt(point)), channelsOf(OVERLAY_COLOR), "$what on $frame")
      }
      outside.forEach { (what, point) ->
        assertReads(
          writtenFrames(output.path, probe = blockAt(point)),
          channelsOf(AuthoredColors.MID_GREY),
          "$what on $frame",
          CHANNEL_DRIFT,
        )
      }
    }

  // The top left of a probe block centred on [point], on the even grid 4:2:0 chroma sits on.
  private fun blockAt(point: Pair<Float, Float>): Pair<Int, Int> =
    (point.first.roundToInt() - PROBE_SIDE / 2) / 2 * 2 to (point.second.roundToInt() - PROBE_SIDE / 2) / 2 * 2

  private companion object {
    const val RATE = 30
    const val SCALE = 0.25f
    val POINT = Anchor(0.3f, 0.6f)
    val LANDSCAPE = Size(640, 360)
    val PORTRAIT = Size(360, 640)

    // How far from an edge a probe block is centred, clear of the 4:2:0 chroma a sharp edge smears.
    const val EDGE = 10f
  }
}
