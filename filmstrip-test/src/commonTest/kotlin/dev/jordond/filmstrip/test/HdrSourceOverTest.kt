package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.authoredHdrNits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where an overlay drawn over an HDR grade should land, in display light, read on every cell of [HdrOverlayCells].
 *
 * Read at half and a quarter opacity, the middle of the ramp, since the ends agree under any reading of the blend.
 */
class HdrSourceOverTest {
  @Test
  fun whiteOverBlackLandsOnReferenceWhiteScaledByOpacity() {
    mapOf(0.5f to 2f, 0.25f to 4f).forEach { (opacity, divisor) ->
      val cell = HdrOverlayCells.Cell(HdrOverlayCells.WHITE, HdrOverlayCells.BLACK, opacity)
      assertTrue(cell in HdrOverlayCells.CELLS, "$cell")

      cell.nits().forEach { channel -> channel shouldBeNear HDR_REFERENCE_WHITE_NITS / divisor }
    }
  }

  @Test
  fun landsHalfwayBetweenOverlayAndBackgroundAtHalfOpacity() {
    cellsAt(0.5f).forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val under = authoredHdrNits(cell.background)
      val landed = cell.nits()

      repeat(3) { channel -> landed[channel] shouldBeNear (over[channel] + under[channel]) / 2f }
    }
  }

  @Test
  fun landsAQuarterOfTheWayToTheOverlayAtAQuarterOpacity() {
    cellsAt(0.25f).forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val under = authoredHdrNits(cell.background)
      val landed = cell.nits()

      repeat(3) { channel -> landed[channel] shouldBeNear under[channel] + (over[channel] - under[channel]) / 4f }
    }
  }

  @Test
  fun landsOnTheOverlaysOwnLightScaledByOpacityOverBlack() {
    val overBlack = HdrOverlayCells.CELLS.filter { it.background == HdrOverlayCells.BLACK }
    assertTrue(overBlack.any { it.opacity == 0.5f } && overBlack.any { it.opacity == 0.25f })

    overBlack.forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val landed = cell.nits()
      val divisor = if (cell.opacity == 0.5f) 2f else 4f

      repeat(3) { channel -> landed[channel] shouldBeNear over[channel] / divisor }
    }
  }

  @Test
  fun landsWhereAFillOfTheSameColourDoesWhenOpaque() {
    cellsAt(1f).forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val landed = cell.nits()

      repeat(3) { channel -> landed[channel] shouldBeNear over[channel] }
    }
  }

  @Test
  fun blendsAreTheCellsThatPartFromTheFillBelowFullOpacity() {
    val overGrey =
      listOf(0.5f, 0.25f).flatMap { opacity ->
        listOf(HdrOverlayCells.WHITE, AuthoredColours.MID_GREEN).map { overlay ->
          HdrOverlayCells.Cell(overlay, AuthoredColours.MID_GREY, opacity)
        }
      }
    val overBlack =
      listOf(0.5f, 0.25f).flatMap { opacity ->
        listOf(HdrOverlayCells.WHITE, AuthoredColours.MID_GREY).map { overlay ->
          HdrOverlayCells.Cell(overlay, HdrOverlayCells.BLACK, opacity)
        }
      }

    assertEquals((overGrey + overBlack).toSet(), HdrOverlayCells.BLENDS.toSet())
    assertEquals(HdrOverlayCells.BLENDS.size, HdrOverlayCells.BLENDS.toSet().size)
  }

  @Test
  fun greyOverGreyIsReadButIsNotABlend() {
    listOf(0.5f, 0.25f).forEach { opacity ->
      val cell = HdrOverlayCells.Cell(AuthoredColours.MID_GREY, AuthoredColours.MID_GREY, opacity)

      assertTrue(cell in HdrOverlayCells.CELLS, "$cell")
      assertFalse(cell in HdrOverlayCells.BLENDS, "$cell")
    }
  }

  @Test
  fun anOverlayDifferingFromTheFillOnlyInAlphaIsNotABlend() {
    val translucentGrey = AuthoredColours.MID_GREY and 0x00FFFFFF or 0x80000000.toInt()

    assertFalse(HdrOverlayCells.Cell(translucentGrey, AuthoredColours.MID_GREY, 0.5f).isBlend)
    assertTrue(HdrOverlayCells.Cell(translucentGrey, AuthoredColours.MID_GREEN, 0.5f).isBlend)
  }

  @Test
  fun lightLandsInTheMiddleOfTheRampAtHalfAndAQuarter() {
    val under = floatArrayOf(100f, 50f, 0f)
    val over = floatArrayOf(300f, 50f, 1000f)

    assertContentEquals(floatArrayOf(200f, 50f, 500f), OverlayPixels.hdrSourceOver(under, over, 0.5f))
    assertContentEquals(floatArrayOf(150f, 50f, 250f), OverlayPixels.hdrSourceOver(under, over, 0.25f))
  }

  @Test
  fun theIntOverloadBlendsTheNitsOfItsColour() {
    HdrOverlayCells.CELLS.forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val under = authoredHdrNits(cell.background)
      val blended = FloatArray(3) { cell.opacity * over[it] + (1f - cell.opacity) * under[it] }

      assertContentEquals(blended, OverlayPixels.hdrSourceOver(under, cell.overlay, cell.opacity), "$cell")
      assertContentEquals(blended, OverlayPixels.hdrSourceOver(under, over, cell.opacity), "$cell")
      assertContentEquals(blended, cell.nits(), "$cell")
    }
  }

  private fun cellsAt(opacity: Float): List<HdrOverlayCells.Cell> =
    HdrOverlayCells.CELLS.filter { it.opacity == opacity }.also { assertTrue(it.isNotEmpty()) }

  private infix fun Float.shouldBeNear(expected: Float) {
    assertTrue(abs(this - expected) <= abs(expected) * 1e-5f + 1e-5f, "expected $expected but was $this")
  }
}
