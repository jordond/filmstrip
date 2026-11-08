package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

class TenBitCodesTest {
  // Black and white pin the luma range, pure red pins how far chroma travels, and a signal past either
  // end pins that end's clamp. The mid color is where a matrix reading the wrong channel would part
  // from the right one.
  @Test
  fun `a signal lands on the ten-bit video range codes BT2020 defines`() {
    tenBitCodesFromSignal(floatArrayOf(0f, 0f, 0f)) shouldBe intArrayOf(64, 512, 512)
    tenBitCodesFromSignal(floatArrayOf(1f, 1f, 1f)) shouldBe intArrayOf(940, 512, 512)
    tenBitCodesFromSignal(floatArrayOf(1f, 0f, 0f)) shouldBe intArrayOf(294, 387, 960)
    tenBitCodesFromSignal(floatArrayOf(0.4272f, 0.462f, 0.523f)) shouldBe intArrayOf(464, 544, 494)
    tenBitCodesFromSignal(floatArrayOf(2f, 2f, 2f)) shouldBe intArrayOf(1023, 512, 512)
    tenBitCodesFromSignal(floatArrayOf(-1f, -1f, -1f)) shouldBe intArrayOf(0, 512, 512)
  }

  // A round trip through the middle of the range, where a matrix reading the wrong channel parts
  // from the right one. The ends come back under either reading, so they are not the case worth
  // holding. Rounding to the nearest code is all the trip may cost.
  @Test
  fun `the ten-bit codes decode back to the signal they were made from`() {
    val signals =
      listOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0.4272f, 0.462f, 0.523f),
      )

    signals.forEach { signal ->
      val codes = tenBitCodesFromSignal(signal)
      val decoded = signalFromTenBitCodes(codes[0], codes[1], codes[2])

      decoded.forEachIndexed { channel, value ->
        withClue(
          "channel $channel came back $value from ${codes.toList()}, where it was encoded from ${signal[channel]}",
        ) {
          (abs(value - signal[channel]) <= CODE_TOLERANCE).shouldBeTrue()
        }
      }
    }
  }

  @Test
  fun `a code outside video range decodes onto the end it ran past`() {
    signalFromTenBitCodes(TEN_BIT_MAX_CODE, 512, 512) shouldBe floatArrayOf(1f, 1f, 1f)
    signalFromTenBitCodes(0, 512, 512) shouldBe floatArrayOf(0f, 0f, 0f)
  }

  // Every ten-bit code widens onto its own sixteen-bit step and the top one stays inside sixteen bits.
  @Test
  fun `a ten-bit code widens onto sixteen bits without running past them`() {
    SIXTEEN_BIT_MAX shouldBe 65_535
    (TEN_BIT_MAX_CODE + 1) * TEN_TO_SIXTEEN_BITS shouldBe SIXTEEN_BIT_MAX + 1
    (TEN_BIT_MAX_CODE * TEN_TO_SIXTEEN_BITS <= SIXTEEN_BIT_MAX).shouldBeTrue()
  }

  private companion object {
    // Half a ten-bit luma code plus half a chroma one, once chroma is scaled back into a channel.
    const val CODE_TOLERANCE = 0.002f
  }
}
