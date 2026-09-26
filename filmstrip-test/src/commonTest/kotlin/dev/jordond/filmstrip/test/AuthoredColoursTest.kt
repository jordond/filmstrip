package dev.jordond.filmstrip.test

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colour-managed predictions and the tolerance every backend's authored-colour pins read against.
 *
 * Both directions are pinned at mid grey against the figures Core Image was measured writing there,
 * and at both ends of the range, where colour management leaves a code where it was.
 */
class AuthoredColoursTest {
  @Test
  fun predictsAManagedMidGreyAtItsMeasuredCode() {
    val managed = AuthoredColours.colourManaged(MID_CODE)

    assertTrue(abs(managed - MANAGED_MID_CODE) < HALF_CODE, "a managed mid grey predicted $managed")
  }

  @Test
  fun predictsAMidGreyManagedFromTheFrameAtItsMeasuredCode() {
    val managed = AuthoredColours.colourManagedFromFrame(MID_CODE)

    assertTrue(abs(managed - FROM_FRAME_MID_CODE) < HALF_CODE, "a mid grey managed from the frame predicted $managed")
  }

  @Test
  fun leavesBothEndsOfTheRangeWhereTheyWere() {
    listOf(0, MAX_CODE).forEach { code ->
      val managed = AuthoredColours.colourManaged(code)
      val fromFrame = AuthoredColours.colourManagedFromFrame(code)

      assertTrue(abs(managed - code) < HALF_CODE, "a managed $code predicted $managed")
      assertTrue(abs(fromFrame - code) < HALF_CODE, "$code managed from the frame predicted $fromFrame")
    }
  }

  @Test
  fun keepsTheToleranceUnderHalfTheSmallestMidRangeGap() {
    val gaps =
      AuthoredColours.COLOURS
        .flatMap { AuthoredColours.channelsOf(it) }
        .filter { it in AuthoredColours.MID_RANGE }
        .flatMap { code ->
          listOf(
            abs(AuthoredColours.colourManaged(code) - code),
            abs(AuthoredColours.colourManagedFromFrame(code) - code),
          )
        }

    assertTrue(gaps.isNotEmpty(), "no authored channel sits in the mid range")
    assertTrue(AuthoredColours.TOLERANCE > 0f, "the tolerance is ${AuthoredColours.TOLERANCE}")
    assertTrue(
      AuthoredColours.TOLERANCE < gaps.min() / 2,
      "the tolerance ${AuthoredColours.TOLERANCE} reaches half the smallest gap ${gaps.min()}",
    )
  }

  @Test
  fun givesEveryColourAMidRangeChannel() {
    AuthoredColours.COLOURS.forEach { argb ->
      val channels = AuthoredColours.channelsOf(argb)

      assertTrue(channels.any { it in AuthoredColours.MID_RANGE }, "$channels has no mid-range channel")
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
