package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * An image overlay placed at a point lands its centre there in the file media3 writes.
 */
class AndroidOverlayPointMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aPictureAtAPointCoversTheRectanglePlacedOnGivesOnALandscapeFrame() =
    runTest(timeout = TIMEOUT) { assertLandsWherePlaced(LANDSCAPE) }

  @Test
  fun aPictureAtAPointCoversTheRectanglePlacedOnGivesOnAPortraitFrame() =
    runTest(timeout = TIMEOUT) { assertLandsWherePlaced(PORTRAIT) }

  private suspend fun assertLandsWherePlaced(frame: Size) {
    val overlay =
      ImageOverlay(ImageSource.of(badgeFile(probe.context).path), OverlayPosition.At(POINT), scale = SCALE)
    val edit =
      compositionOf {
        solid(AuthoredColors.MID_GREY, 1.seconds)
        frame(OutputFrame.Fixed(frame))
        effects { add(overlay) }
      }
    val plan = probe.plan(edit, ExportSpec())
    val written = probe.writtenBy(probe.finish(plan))

    val size = plan.output.size
    val rect = overlay.placedOn(size, BADGE).rectOn(size)
    val edgeX = EDGE_PX / size.width
    val edgeY = EDGE_PX / size.height
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
    inside.forEach { (what, point) -> assertReads(written, point, BADGE_COLOR, "$what on $frame") }
    outside.forEach { (what, point) -> assertReads(written, point, AuthoredColors.MID_GREY, "$what on $frame") }
  }

  private fun assertReads(
    written: Written,
    point: Pair<Float, Float>,
    color: Int,
    what: String,
  ) {
    val read = written.rgbAt(point.first, point.second).toList()
    val expected = AuthoredColors.channelsOf(color)
    withClue("$what read $read, expected $expected") {
      read.zip(expected).all { (got, want) -> abs(got - want) <= DRIFT }.shouldBeTrue()
    }
  }

  private companion object {
    const val SCALE = 0.25f
    val POINT = Anchor(0.3f, 0.6f)
    val LANDSCAPE = Size(640, 360)
    val PORTRAIT = Size(360, 640)

    // How far a channel may read off its colour after a 4:2:0 encode. The badge and the solid sit over 100 codes
    // apart on red and blue, so a reading on the wrong side of an edge cannot pass.
    const val DRIFT = 12

    // How far from an edge a reading is centred, clear of the 4:2:0 chroma a sharp edge smears.
    const val EDGE_PX = 10f
  }
}
