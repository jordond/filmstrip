package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.TestScratch
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An image overlay placed at a point lands its centre there in the file AVFoundation writes.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayPointHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a picture at a point covers the rectangle placedOn gives on a landscape frame`() =
    runTest(timeout = TIMEOUT) { assertLandsWherePlaced(LANDSCAPE) }

  @Test
  fun `a picture at a point covers the rectangle placedOn gives on a portrait frame`() =
    runTest(timeout = TIMEOUT) { assertLandsWherePlaced(PORTRAIT) }

  private suspend fun assertLandsWherePlaced(frame: Size) {
    val output = TestScratch.directory + "filmstrip-apple-overlay-point-${frame.width}x${frame.height}.mp4"
    val overlay =
      ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(PICTURE, COLOR)), OverlayPosition.At(POINT), scale = SCALE)
    val edit =
      compositionOf {
        solid(AuthoredColors.MID_GREY, 1.seconds)
        frame(OutputFrame.Fixed(frame))
        effects { add(overlay) }
      }

    val success =
      withContext(Dispatchers.Default) {
        filmstrip.export(edit, ExportSpec(), MediaSink.of(output)).toList().last()
      }.shouldBeInstanceOf<ExportStatus.Success>()

    val size = success.info.video?.displaySize
    size shouldBe frame
    val drawn = codesOf(output)
    val rect = overlay.placedOn(frame, PICTURE).rectOn(frame)
    val edgeX = EDGE_PX / frame.width
    val edgeY = EDGE_PX / frame.height
    val middleX = (rect.left + rect.right) / 2
    val middleY = (rect.top + rect.bottom) / 2
    val inside =
      mapOf(
        "the centre" to (middleX to middleY),
        "inside the start edge" to (rect.left + edgeX to middleY),
        "inside the end edge" to (rect.right - edgeX to middleY),
        "inside the top edge" to (middleX to rect.top + edgeY),
        "inside the bottom edge" to (middleX to rect.bottom - edgeY),
      )
    val outside =
      mapOf(
        "past the start edge" to (rect.left - edgeX to middleY),
        "past the end edge" to (rect.right + edgeX to middleY),
        "above the top edge" to (middleX to rect.top - edgeY),
        "below the bottom edge" to (middleX to rect.bottom + edgeY),
      )
    inside.forEach { (what, point) -> assertReads(drawn, point, COLOR, "$what on $frame") }
    outside.forEach { (what, point) -> assertReads(drawn, point, AuthoredColors.MID_GREY, "$what on $frame") }
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  private fun assertReads(
    drawn: FrameProbe,
    point: Pair<Float, Float>,
    color: Int,
    what: String,
  ) {
    val read = drawn.average(point.first, point.second).toList()
    val expected = AuthoredColors.channelsOf(color)
    withClue("$what read $read, expected $expected") {
      read.zip(expected).all { (got, want) -> abs(got - want) <= DRIFT }.shouldBeTrue()
    }
  }

  private companion object {
    val TIMEOUT = 2.minutes
    const val SCALE = 0.25f
    const val COLOR = 0xFFE67814.toInt()
    val PICTURE = Size(64, 64)
    val POINT = Anchor(0.3f, 0.6f)
    val LANDSCAPE = Size(640, 360)
    val PORTRAIT = Size(360, 640)

    // How far a channel may read off its colour after a 4:2:0 encode. The picture and the solid sit about 100 codes
    // apart on red and blue, so a reading on the wrong side of an edge cannot pass.
    const val DRIFT = 12

    // How far from an edge a reading is centred, clear of the 4:2:0 chroma a sharp edge smears.
    const val EDGE_PX = 10f
  }
}
