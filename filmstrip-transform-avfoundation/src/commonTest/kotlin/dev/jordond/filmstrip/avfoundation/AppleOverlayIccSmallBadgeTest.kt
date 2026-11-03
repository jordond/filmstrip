package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Color described overlay pictures drawn as small badges onto the frame Core Image hands an SDR export's encoder.
 *
 * A badge is held exact to its sRGB codes, all but the HEIC and JPEG ImageIO wrote, which decode a little off the texel
 * they were written with.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayIccSmallBadgeTest : AppleOverlayIccCase() {
  @Test
  fun `a small badge is drawn at the shared srgb codes`() =
    runTest(timeout = TIMEOUT) {
      val context = floatContext()
      val misses = mutableListOf<String>()

      PICTURES.chunked(CORNERS.size).forEach { pictures ->
        val badges = pictures.zip(CORNERS) { picture, corner -> picture.badgeAt(corner, SMALL_SCALE) }
        val drawnOverlays = badges.zip(pictures.map { it.degradedBy })
        val drawn =
          drawnSdrFrame(FRAME, AuthoredColors.MID_GREY, drawnOverlays, TimeRange.of(Duration.ZERO, CLIP), CLIP / 2)
        pictures.forEach { it.removeFile() }

        pictures.zip(badges).filter { (picture, _) -> picture !in ENCODED }.mapNotNullTo(misses) { (picture, badge) ->
          val rect = badge.placedOn(FRAME, picture.size).rectOn(FRAME)
          val x = (rect.left + rect.right) / 2f
          val y = (rect.top + rect.bottom) / 2f
          AppleStillSamples.missOrUnmeasured {
            val colors = picture.srgbCodes()
            val read = drawn.sdrCodesAt(context, FRAME, x, y)
            "the small ${picture.name} badge was drawn at $read, where its color is one of $colors".takeIf {
              colors.none { color -> read.indices.all { abs(read[it] - color[it]) <= DRAWN_TOLERANCE } }
            }
          }
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  private companion object {
    // The landscape frame an SDR export lays at its 180 rows.
    val FRAME = Size(320, 180)
  }
}
