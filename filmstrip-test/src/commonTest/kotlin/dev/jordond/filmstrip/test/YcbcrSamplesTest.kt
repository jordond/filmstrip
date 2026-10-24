package dev.jordond.filmstrip.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Decodes one mid YCbCr sample, 128, 96 and 176, under every matrix core reads and checks it against codes worked out
 * by hand from the H.273 equations.
 */
class YcbcrSamplesTest {
  private fun decode(
    matrix: Int,
    fullRange: Boolean,
    primaries: Int = BT709_PRIMARIES,
    transfer: Int = SRGB_TRANSFER,
  ): List<Int> = ycbcrToRgb(128, 96, 176, matrix, primaries, transfer, fullRange).toList()

  private fun bt709(
    y: Int,
    cb: Int,
    cr: Int,
    fullRange: Boolean,
    bitDepth: Int = 8,
  ): List<Int> = ycbcrToRgb(y, cb, cr, BT709, BT709_PRIMARIES, SRGB_TRANSFER, fullRange, bitDepth).toList()

  @Test
  fun identityPutsGreenInLumaAndScalesEveryPlaneAsLuma() {
    assertEquals(listOf(176, 128, 96), decode(0, fullRange = true))
    // (176 - 16) / 219 * 255 = 186.3, (128 - 16) / 219 * 255 = 130.4, (96 - 16) / 219 * 255 = 93.2
    assertEquals(listOf(186, 130, 93), decode(0, fullRange = false))
  }

  @Test
  fun bt709() {
    // R = 128 + 1.5748 * 48 = 203.6, B = 128 + 1.8556 * -32 = 68.6, G = (128 - 0.2126 R - 0.0722 B) / 0.7152 = 111.5
    assertEquals(listOf(204, 112, 69), decode(1, fullRange = true))
    assertEquals(listOf(216, 112, 63), decode(1, fullRange = false))
  }

  @Test
  fun fcc() {
    assertEquals(listOf(195, 104, 71), decode(4, fullRange = true))
    assertEquals(listOf(207, 104, 66), decode(4, fullRange = false))
  }

  @Test
  fun bt601ForFiveSixAndUnspecified() {
    for (matrix in listOf(2, 5, 6)) {
      // R = 128 + 1.402 * 48 = 195.3, B = 128 + 1.772 * -32 = 71.3, G = (128 - 0.299 R - 0.114 B) / 0.587 = 104.7
      assertEquals(listOf(195, 105, 71), decode(matrix, fullRange = true), "matrix $matrix")
      assertEquals(listOf(207, 104, 66), decode(matrix, fullRange = false), "matrix $matrix")
    }
  }

  @Test
  fun smpte240m() {
    assertEquals(listOf(204, 112, 70), decode(7, fullRange = true))
    assertEquals(listOf(217, 113, 64), decode(7, fullRange = false))
  }

  @Test
  fun yCgCoScalesItsChromaAsLuma() {
    // Cg = -32, Co = 48, so R = 128 + 32 + 48, G = 128 - 32, B = 128 + 32 - 48
    assertEquals(listOf(208, 96, 112), decode(8, fullRange = true))
    // Luma, Cg and Co each over 219 codes: R = 223.6, G = 93.2, B = 111.8
    assertEquals(listOf(224, 93, 112), decode(8, fullRange = false))
  }

  @Test
  fun bt2020NonConstantLuminance() {
    // R = 128 + 1.4746 * 48 = 198.8, B = 128 + 1.8814 * -32 = 67.8, G = (128 - 0.2627 R - 0.0593 B) / 0.678 = 105.8
    assertEquals(listOf(199, 106, 68), decode(9, fullRange = true))
    assertEquals(listOf(211, 105, 62), decode(9, fullRange = false))
  }

  @Test
  fun bt2020ConstantLuminanceTakesItsTransfersCurve() {
    // Red over 0.9938 above zero, R = 128 + 0.9938 * 48 = 175.7, blue over 1.9403 below it, B = 128 - 1.9403 * 32 =
    // 65.9, and green from linear light through the BT.709 curve, G = 108.6
    assertEquals(listOf(176, 109, 66), decode(10, fullRange = true, BT2020_PRIMARIES, BT2020_TRANSFER))
    assertEquals(listOf(185, 107, 60), decode(10, fullRange = false, BT2020_PRIMARIES, BT2020_TRANSFER))
  }

  @Test
  fun chromaticityDerivedMatricesReadAsTheNamedOneTheirPrimariesGive() {
    for (fullRange in listOf(true, false)) {
      assertEquals(decode(1, fullRange), decode(12, fullRange), "full $fullRange")
      assertEquals(
        decode(9, fullRange, BT2020_PRIMARIES, BT2020_TRANSFER),
        decode(12, fullRange, BT2020_PRIMARIES, BT2020_TRANSFER),
        "full $fullRange",
      )
      assertEquals(
        decode(10, fullRange, BT2020_PRIMARIES, BT2020_TRANSFER),
        decode(13, fullRange, BT2020_PRIMARIES, BT2020_TRANSFER),
        "full $fullRange",
      )
    }
  }

  @Test
  fun tenBitSamplesScaleTheirRanges() {
    assertEquals(listOf(216, 112, 63), bt709(512, 384, 704, fullRange = false, bitDepth = 10))
    // Full range 10-bit is normalised over 1023 around 512, so it lands a little off the 8-bit codes
    assertEquals(listOf(203, 111, 68), bt709(512, 384, 704, fullRange = true, bitDepth = 10))
  }

  @Test
  fun samplesPastTheRangeClipToItsEnds() {
    assertEquals(listOf(0, 0, 0), bt709(0, 128, 128, fullRange = false))
    assertEquals(listOf(255, 255, 255), bt709(255, 128, 128, fullRange = false))
  }

  @Test
  fun matricesCoreRefusesAndOddBitDepthsThrow() {
    for (matrix in listOf(3, 11, 14, 15, 255)) {
      assertFailsWith<IllegalArgumentException>("matrix $matrix") { decode(matrix, fullRange = true) }
    }
    assertFailsWith<IllegalArgumentException> { decode(12, fullRange = true, primaries = UNSPECIFIED) }
    assertFailsWith<IllegalArgumentException> { decode(10, fullRange = true, BT2020_PRIMARIES, PQ_TRANSFER) }
    assertFailsWith<IllegalArgumentException> { bt709(128, 128, 128, fullRange = true, bitDepth = 7) }
  }

  private companion object {
    const val BT709 = 1
    const val UNSPECIFIED = 2
    const val BT709_PRIMARIES = 1
    const val BT2020_PRIMARIES = 9
    const val SRGB_TRANSFER = 13
    const val BT2020_TRANSFER = 14
    const val PQ_TRANSFER = 16
  }
}
