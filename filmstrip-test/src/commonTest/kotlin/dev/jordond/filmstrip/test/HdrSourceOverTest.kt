package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.authoredHdrNits
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

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
      withClue("$cell") { (cell in HdrOverlayCells.CELLS) shouldBe true }

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
    (overBlack.any { it.opacity == 0.5f } && overBlack.any { it.opacity == 0.25f }) shouldBe true

    overBlack.forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val landed = cell.nits()
      val divisor = if (cell.opacity == 0.5f) 2f else 4f

      repeat(3) { channel -> landed[channel] shouldBeNear over[channel] / divisor }
    }
  }

  @Test
  fun landsWhereAFillOfTheSameColorDoesWhenOpaque() {
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
        listOf(HdrOverlayCells.WHITE, AuthoredColors.MID_GREEN).map { overlay ->
          HdrOverlayCells.Cell(overlay, AuthoredColors.MID_GREY, opacity)
        }
      }
    val overBlack =
      listOf(0.5f, 0.25f).flatMap { opacity ->
        listOf(HdrOverlayCells.WHITE, AuthoredColors.MID_GREY).map { overlay ->
          HdrOverlayCells.Cell(overlay, HdrOverlayCells.BLACK, opacity)
        }
      }

    HdrOverlayCells.BLENDS.toSet() shouldBe (overGrey + overBlack).toSet()
    HdrOverlayCells.BLENDS.toSet().size shouldBe HdrOverlayCells.BLENDS.size
  }

  @Test
  fun greyOverGreyIsReadButIsNotABlend() {
    listOf(0.5f, 0.25f).forEach { opacity ->
      val cell = HdrOverlayCells.Cell(AuthoredColors.MID_GREY, AuthoredColors.MID_GREY, opacity)

      withClue("$cell") { (cell in HdrOverlayCells.CELLS) shouldBe true }
      withClue("$cell") { (cell in HdrOverlayCells.BLENDS) shouldBe false }
    }
  }

  @Test
  fun anOverlayDifferingFromTheFillOnlyInAlphaIsNotABlend() {
    val translucentGrey = AuthoredColors.MID_GREY and 0x00FFFFFF or 0x80000000.toInt()

    HdrOverlayCells.Cell(translucentGrey, AuthoredColors.MID_GREY, 0.5f).isBlend shouldBe false
    HdrOverlayCells.Cell(translucentGrey, AuthoredColors.MID_GREEN, 0.5f).isBlend shouldBe true
  }

  @Test
  fun lightLandsInTheMiddleOfTheRampAtHalfAndAQuarter() {
    val under = floatArrayOf(100f, 50f, 0f)
    val over = floatArrayOf(300f, 50f, 1000f)

    OverlayPixels.hdrSourceOver(under, over, 0.5f) shouldBe floatArrayOf(200f, 50f, 500f)
    OverlayPixels.hdrSourceOver(under, over, 0.25f) shouldBe floatArrayOf(150f, 50f, 250f)
  }

  @Test
  fun theIntOverloadBlendsTheNitsOfItsColor() {
    HdrOverlayCells.CELLS.forEach { cell ->
      val over = authoredHdrNits(cell.overlay)
      val under = authoredHdrNits(cell.background)
      val blended = FloatArray(3) { cell.opacity * over[it] + (1f - cell.opacity) * under[it] }

      withClue("$cell") { OverlayPixels.hdrSourceOver(under, cell.overlay, cell.opacity) shouldBe blended }
      withClue("$cell") { OverlayPixels.hdrSourceOver(under, over, cell.opacity) shouldBe blended }
      withClue("$cell") { cell.nits() shouldBe blended }
    }
  }

  private fun cellsAt(opacity: Float): List<HdrOverlayCells.Cell> =
    HdrOverlayCells.CELLS.filter { it.opacity == opacity }.also { it.isNotEmpty() shouldBe true }

  private infix fun Float.shouldBeNear(expected: Float) {
    withClue("expected $expected but was $this") {
      (abs(this - expected) <= abs(expected) * 1e-5f + 1e-5f) shouldBe true
    }
  }
}
