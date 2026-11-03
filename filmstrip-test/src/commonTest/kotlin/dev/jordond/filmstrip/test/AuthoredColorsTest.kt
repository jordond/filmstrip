package dev.jordond.filmstrip.test

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * The color-managed predictions and the tolerance every backend's authored-color pins read against.
 *
 * Both directions are pinned at mid grey against the figures Core Image was measured writing there,
 * and at both ends of the range, where color management leaves a code where it was.
 */
class AuthoredColorsTest {
  @Test
  fun predictsAManagedMidGreyAtItsMeasuredCode() {
    val managed = AuthoredColors.colorManaged(MID_CODE)

    withClue("a managed mid grey predicted $managed") { (abs(managed - MANAGED_MID_CODE) < HALF_CODE) shouldBe true }
  }

  @Test
  fun predictsAMidGreyManagedFromTheFrameAtItsMeasuredCode() {
    val managed = AuthoredColors.colorManagedFromFrame(MID_CODE)

    withClue("a mid grey managed from the frame predicted $managed") {
      (abs(managed - FROM_FRAME_MID_CODE) < HALF_CODE) shouldBe true
    }
  }

  @Test
  fun leavesBothEndsOfTheRangeWhereTheyWere() {
    listOf(0, MAX_CODE).forEach { code ->
      val managed = AuthoredColors.colorManaged(code)
      val fromFrame = AuthoredColors.colorManagedFromFrame(code)

      withClue("a managed $code predicted $managed") { (abs(managed - code) < HALF_CODE) shouldBe true }
      withClue("$code managed from the frame predicted $fromFrame") {
        (abs(fromFrame - code) < HALF_CODE) shouldBe true
      }
    }
  }

  @Test
  fun keepsTheToleranceUnderHalfTheSmallestMidRangeGap() {
    val gaps =
      AuthoredColors.COLORS
        .flatMap { AuthoredColors.channelsOf(it) }
        .filter { it in AuthoredColors.MID_RANGE }
        .flatMap { code ->
          listOf(
            abs(AuthoredColors.colorManaged(code) - code),
            abs(AuthoredColors.colorManagedFromFrame(code) - code),
          )
        }

    withClue("no authored channel sits in the mid range") { gaps.isNotEmpty() shouldBe true }
    withClue("the tolerance is ${AuthoredColors.TOLERANCE}") { (AuthoredColors.TOLERANCE > 0f) shouldBe true }
    withClue("the tolerance ${AuthoredColors.TOLERANCE} reaches half the smallest gap ${gaps.min()}") {
      (AuthoredColors.TOLERANCE < gaps.min() / 2) shouldBe true
    }
  }

  @Test
  fun givesEveryColorAMidRangeChannel() {
    AuthoredColors.COLORS.forEach { argb ->
      val channels = AuthoredColors.channelsOf(argb)

      withClue("$channels has no mid-range channel") { channels.any { it in AuthoredColors.MID_RANGE } shouldBe true }
    }
  }

  private companion object {
    const val MID_CODE = 128
    const val MANAGED_MID_CODE = 134.6f
    const val FROM_FRAME_MID_CODE = 121f
    const val MAX_CODE = 255
    const val HALF_CODE = 0.5f
  }
}
