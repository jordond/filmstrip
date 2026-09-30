package dev.jordond.filmstrip.transform.internal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HdrFillTest {
  // Black and white pin the luma range, pure red pins how far chroma travels, and a signal past either
  // end pins that end's clamp. The mid colour is where a matrix reading the wrong channel would part
  // from the right one.
  @Test
  fun `a signal lands on the ten-bit video range codes BT2020 defines`() {
    assertContentEquals(intArrayOf(64, 512, 512), tenBitCodesFromSignal(floatArrayOf(0f, 0f, 0f)))
    assertContentEquals(intArrayOf(940, 512, 512), tenBitCodesFromSignal(floatArrayOf(1f, 1f, 1f)))
    assertContentEquals(intArrayOf(294, 387, 960), tenBitCodesFromSignal(floatArrayOf(1f, 0f, 0f)))
    assertContentEquals(intArrayOf(464, 544, 494), tenBitCodesFromSignal(floatArrayOf(0.4272f, 0.462f, 0.523f)))
    assertContentEquals(intArrayOf(1023, 512, 512), tenBitCodesFromSignal(floatArrayOf(2f, 2f, 2f)))
    assertContentEquals(intArrayOf(0, 512, 512), tenBitCodesFromSignal(floatArrayOf(-1f, -1f, -1f)))
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
        assertTrue(
          abs(value - signal[channel]) <= CODE_TOLERANCE,
          "channel $channel came back $value from ${codes.toList()}, where it was encoded from ${signal[channel]}",
        )
      }
    }
  }

  @Test
  fun `a code outside video range decodes onto the end it ran past`() {
    assertContentEquals(floatArrayOf(1f, 1f, 1f), signalFromTenBitCodes(TEN_BIT_MAX_CODE, 512, 512))
    assertContentEquals(floatArrayOf(0f, 0f, 0f), signalFromTenBitCodes(0, 512, 512))
  }

  private companion object {
    // Half a ten-bit luma code plus half a chroma one, once chroma is scaled back into a channel.
    const val CODE_TOLERANCE = 0.002f
  }
}
