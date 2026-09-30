package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.media.authoredHdrNits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where an overlay drawn over an HDR grade should land, in display light.
 *
 * Read at half and a quarter opacity, the middle of the ramp, since the ends agree under any reading of
 * the blend.
 */
class HdrSourceOverTest {
  @Test
  fun landsHalfwayBetweenOverlayAndBackgroundAtHalfOpacity() {
    OVERLAYS.forEach { overlay ->
      val over = authoredHdrNits(overlay)
      val landed = OverlayPixels.hdrSourceOver(BACKGROUND, overlay, 0.5f)

      repeat(3) { channel -> landed[channel] shouldBeNear (over[channel] + BACKGROUND[channel]) / 2f }
    }
  }

  @Test
  fun landsAQuarterOfTheWayToTheOverlayAtAQuarterOpacity() {
    OVERLAYS.forEach { overlay ->
      val over = authoredHdrNits(overlay)
      val landed = OverlayPixels.hdrSourceOver(BACKGROUND, overlay, 0.25f)

      repeat(3) { channel ->
        landed[channel] shouldBeNear BACKGROUND[channel] + (over[channel] - BACKGROUND[channel]) / 4f
      }
    }
  }

  @Test
  fun landsWhereAFillOfTheSameColourDoesWhenOpaque() {
    OVERLAYS.forEach { overlay ->
      val over = authoredHdrNits(overlay)
      val landed = OverlayPixels.hdrSourceOver(BACKGROUND, overlay, 1f)

      repeat(3) { channel -> landed[channel] shouldBeNear over[channel] }
    }
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    assertTrue(abs(this - expected) <= abs(expected) * 1e-5f + 1e-5f, "expected $expected but was $this")
  }

  private companion object {
    val BACKGROUND = authoredHdrNits(AuthoredColours.MID_GREY)
    val OVERLAYS = listOf(0xFFFFFFFF.toInt(), AuthoredColours.MID_GREY, AuthoredColours.MID_GREEN)
  }
}
