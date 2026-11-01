package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The straight sample tolerance against model stores built here. A sixteen-bit store rounds the premultiplied colour to
 * a whole sample and an eight-bit store rounds it to a whole eight-bit code. Each is read back by dividing by the alpha
 * it stored. The signal bound is checked against the signal of every sample the sample bound holds.
 */
class StraightSamplesTest {
  @Test
  fun aSixteenBitStoreIsOneSampleOverTheAlpha() {
    assertEquals(1f, straightSampleTolerance(0xFF))
    assertEquals(255f / 0x80, straightSampleTolerance(0x80))
    assertEquals(255f / 0x40, straightSampleTolerance(0x40))
    assertEquals(255f, straightSampleTolerance(1))
  }

  @Test
  fun holdsEveryColourASixteenBitStoreReadsBack() {
    for (alpha in 1..MAX_CODE) {
      val tolerance = straightSampleTolerance(alpha)
      for (straight in STRAIGHTS) {
        val error = abs(sixteenBitRead(straight, alpha) - straight)
        assertTrue(error <= tolerance, "$straight at alpha $alpha read $error samples off, past $tolerance")
      }
    }
  }

  @Test
  fun turnsAwayAnEightBitStore() {
    for ((code, alpha) in MIDDLE_POINTS) {
      val error = abs(eightBitRead(code, alpha) - sampleOfCode(code))
      assertTrue(error > straightSampleTolerance(alpha), "code $code at alpha $alpha read only $error samples off")
    }
  }

  @Test
  fun widensACodeToItsSample() {
    assertEquals(0, sampleOfCode(0))
    assertEquals(0x8080, sampleOfCode(0x80))
    assertEquals(MAX_SAMPLE, sampleOfCode(MAX_CODE))
    assertFailsWith<IllegalArgumentException> { sampleOfCode(-1) }
    assertFailsWith<IllegalArgumentException> { sampleOfCode(MAX_CODE + 1) }
  }

  @Test
  fun refusesAnAlphaWithNoColour() {
    assertFailsWith<IllegalArgumentException> { straightSampleTolerance(0) }
    assertFailsWith<IllegalArgumentException> { straightSampleTolerance(MAX_CODE + 1) }
  }

  @Test
  fun holdsTheSignalOfEverySampleInsideTheSampleBound() {
    for (transfer in HdrTransfer.entries) {
      for ((code, alpha) in MIDDLE_POINTS) {
        val straight = sampleOfCode(code)
        val reach = straightSampleTolerance(alpha).toInt()
        val bound = straightSignalTolerance(alpha, code, transfer)
        val authored = greySignal(straight.toFloat(), transfer)
        for (sample in straight - reach..straight + reach) {
          val moved = signalMove(greySignal(sample.toFloat(), transfer), authored)
          assertTrue(moved <= bound, "$transfer sample $sample of code $code moved $moved past $bound")
        }
      }
    }
  }

  @Test
  fun turnsAwayTheSignalOfASampleJustPastTheSampleBound() {
    for (transfer in HdrTransfer.entries) {
      for ((code, alpha) in MIDDLE_POINTS) {
        val straight = sampleOfCode(code)
        val past = straightSampleTolerance(alpha).toInt() + 1
        val bound = straightSignalTolerance(alpha, code, transfer)
        val authored = greySignal(straight.toFloat(), transfer)
        val below = signalMove(greySignal((straight - past).toFloat(), transfer), authored)
        val above = signalMove(greySignal((straight + past).toFloat(), transfer), authored)
        assertTrue(
          maxOf(below, above) > bound,
          "$transfer code $code moved $below and $above $past samples out, inside $bound",
        )
      }
    }
  }

  @Test
  fun widensTheSignalBoundAsTheAlphaFalls() {
    for (transfer in HdrTransfer.entries) {
      val bounds = listOf(0xFF, 0x80, 0x40).map { straightSignalTolerance(it, 0x80, transfer) }
      assertTrue(bounds.zipWithNext().all { (high, low) -> high < low }, "$transfer bounds $bounds")
      assertTrue(bounds.first() > 0f, "$transfer bounds $bounds")
    }
  }

  @Test
  fun refusesASignalBoundForAnAlphaWithNoColourOrACodeOutOfRange() {
    for (transfer in HdrTransfer.entries) {
      assertFailsWith<IllegalArgumentException> { straightSignalTolerance(0, 0x80, transfer) }
      assertFailsWith<IllegalArgumentException> { straightSignalTolerance(MAX_CODE, MAX_CODE + 1, transfer) }
    }
  }

  private fun signalMove(
    signal: FloatArray,
    authored: FloatArray,
  ): Float = signal.indices.maxOf { abs(signal[it] - authored[it]) }

  // Premultiplied and rounded at sixteen bits, then divided back out and rounded to a whole sample.
  private fun sixteenBitRead(
    straight: Int,
    alpha: Int,
  ): Float {
    val wideAlpha = sampleOfCode(alpha)
    val stored = (straight.toDouble() * wideAlpha / MAX_SAMPLE).roundToLong()
    return (stored.toDouble() * MAX_SAMPLE / wideAlpha).roundToLong().toFloat()
  }

  // Premultiplied and rounded at eight bits, then divided back out and widened to sixteen.
  private fun eightBitRead(
    code: Int,
    alpha: Int,
  ): Float {
    val stored = (code.toDouble() * alpha / MAX_CODE).roundToLong()
    return (stored.toDouble() * MAX_CODE / alpha * sampleOfCode(1)).toFloat()
  }

  private companion object {
    const val MAX_CODE = 255
    const val MAX_SAMPLE = 65535

    // Colour equal to alpha, and colour apart from it at a low alpha, both away from the ends of the range.
    val MIDDLE_POINTS = listOf(0x80 to 0x80, 0xC8 to 0x40)

    // Every eight-bit code's sample, and samples between codes across the whole range.
    val STRAIGHTS = (0..MAX_CODE).map(::sampleOfCode) + (1..MAX_SAMPLE step 997)
  }
}
