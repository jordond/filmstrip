package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks where [nearestReadable] lands a picture whose matrix a decoder cannot read, how far [landingError] measures
 * that landing and which side of [NEAREST_MATRIX_DELTA_E_BOUND] each kind of landing falls.
 */
class NearestMatrixTest {
  // A constant luminance curve with no linear toe re-encodes green near black through a steep power, which turns
  // double rounding of about 1e-17 into up to a thousandth of a code on the 2.8 gamma curve.
  @Test
  fun `every reading read through itself lands with no error`() {
    for (reading in READINGS) {
      val error = landingError(reading, reading)
      val tolerance = if (reading is YcbcrReading.ConstantLuminance) 1e-3 else 1e-9
      assertTrue(error.worstError < tolerance, "$reading worst ${error.worstError}")
      assertTrue(error.meanDeltaE < tolerance, "$reading mean ${error.meanDeltaE}")
    }
  }

  @Test
  fun `BT709 samples read through BT601 land where the forward equations put them`() {
    val error = landingError(ycbcrReading(1, 1, 1), ycbcrReading(6, 1, 1))
    assertTrue(error.worstError > 36.0 && error.worstError < 38.0, "worst ${error.worstError}")
    assertTrue(error.meanDeltaE > 5.6 && error.meanDeltaE < 5.9, "mean ${error.meanDeltaE}")
  }

  @Test
  fun `a readable code point that reads the same lands exactly`() {
    ycbcrReading(1, 1, 1).nearestReadable(setOf(1, 6)) shouldBe NearestMatrix(1, 0.0, 0.0, exact = true)
    ycbcrReading(5, 1, 1).nearestReadable(setOf(6, 9)) shouldBe NearestMatrix(6, 0.0, 0.0, exact = true)
    ycbcrReading(12, 9, 1).nearestReadable(setOf(9)) shouldBe NearestMatrix(9, 0.0, 0.0, exact = true)
    ycbcrReading(13, 9, 13).nearestReadable(setOf(1, 10)) shouldBe NearestMatrix(10, 0.0, 0.0, exact = true)
    YcbcrReading.Identity.nearestReadable(setOf(0, 1)) shouldBe NearestMatrix(0, 0.0, 0.0, exact = true)
  }

  @Test
  fun `nearest readable picks the smallest mean colour difference from the shared measure`() {
    val sources = listOf(ycbcrReading(12, 6, 1), ycbcrReading(7, 1, 1), ycbcrReading(13, 12, 13))
    val readable = setOf(1, 6, 9)
    for (source in sources) {
      val nearest = assertNotNull(source.nearestReadable(readable))
      val errors = readable.associateWith { landingError(source, ycbcrReading(it, UNSPECIFIED, transferOf(source))) }
      nearest.meanDeltaE shouldBe errors.values.minOf { it.meanDeltaE }
      errors.getValue(nearest.matrix).worstError shouldBe nearest.worstError
      nearest.exact shouldBe false
    }
  }

  @Test
  fun `constant luminance lands through the code point with its own Kr and Kb`() {
    val bt2020 = ycbcrReading(10, 9, 14)
    val viaOwn = assertNotNull(bt2020.nearestReadable(setOf(6, 9)))
    viaOwn.matrix shouldBe 9
    val via601 = landingError(bt2020, ycbcrReading(6, UNSPECIFIED, 14))
    assertTrue(viaOwn.meanDeltaE < via601.meanDeltaE, "$viaOwn against ${via601.meanDeltaE}")
    assertTrue(viaOwn.meanDeltaE > 6.0 && viaOwn.meanDeltaE < 7.5, "$viaOwn")
    assertTrue(viaOwn.withinBound && !viaOwn.exact)
    assertNotNull(ycbcrReading(13, 1, 13).nearestReadable(setOf(1, 6, 9))).matrix shouldBe 1
    val fallback = assertNotNull(bt2020.nearestReadable(setOf(1, 6)))
    fallback.meanDeltaE shouldBe minOf(via601.meanDeltaE, landingError(bt2020, ycbcrReading(1, 2, 14)).meanDeltaE)
  }

  @Test
  fun `a derived matrix close to a named one lands within a code`() {
    val nearest = assertNotNull(ycbcrReading(12, 6, 1).nearestReadable(setOf(1, 6, 7, 9)))
    nearest.matrix shouldBe 7
    assertTrue(nearest.worstError > 0.0 && nearest.worstError < 1.0, "$nearest")
    assertTrue(nearest.meanDeltaE > 0.0 && nearest.meanDeltaE < 0.5, "$nearest")
    assertTrue(nearest.withinBound && !nearest.exact)
  }

  @Test
  fun `the bound admits every Kr and Kb landing and refuses YCgCo and identity through one`() {
    val named = listOf(1, 4, 6, 7, 9)
    for (source in named.map { ycbcrReading(it, UNSPECIFIED, 1) }) {
      for (landing in named) {
        assertTrue(assertNotNull(source.nearestReadable(setOf(landing))).withinBound, "$source through $landing")
      }
    }
    for (source in listOf(YcbcrReading.YCgCo, YcbcrReading.Identity)) {
      for (landing in named) {
        val nearest = assertNotNull(source.nearestReadable(setOf(landing)))
        assertTrue(!nearest.withinBound && nearest.meanDeltaE > NEAREST_MATRIX_DELTA_E_BOUND, "$source via $landing")
      }
    }
  }

  @Test
  fun `constant luminance on each curve lands on its own side of the bound`() {
    val within = listOf(1, 4, 5, 7, 8, 13, 17)
    val past = listOf(9, 10)
    for (transfer in within + past) {
      val nearest = assertNotNull(ycbcrReading(10, 9, transfer).nearestReadable(setOf(1, 4, 6, 7, 9)))
      nearest.matrix shouldBe 9
      nearest.withinBound shouldBe (transfer in within)
      nearest.withinBound shouldBe (nearest.meanDeltaE <= NEAREST_MATRIX_DELTA_E_BOUND)
    }
  }

  @Test
  fun `the figures print the mean colour difference and the worst channel`() {
    NearestMatrix(9, 6.84, 67.04, exact = false).figures shouldBe
      "a mean colour difference of 6.8 dE76, with one channel up to 67.0 8-bit codes out"
    NearestMatrix(6, 0.0, 0.0, exact = true).figures shouldBe
      "a mean colour difference of 0.0 dE76, with one channel up to 0.0 8-bit codes out"
  }

  @Test
  fun `a repeat lookup gives the landing the first one measured`() {
    val source = ycbcrReading(13, 12, 1)
    val first = assertNotNull(source.nearestReadable(setOf(1, 6, 9)))
    ycbcrReading(13, 12, 1).nearestReadable(setOf(9, 6, 1)) shouldBe first
    val measured = landingError(source, ycbcrReading(first.matrix, UNSPECIFIED, 1))
    first.meanDeltaE shouldBe measured.meanDeltaE
    first.worstError shouldBe measured.worstError
  }

  @Test
  fun `a reading without constant luminance never lands through a constant luminance code point`() {
    for (source in listOf(ycbcrReading(9, UNSPECIFIED, 1), ycbcrReading(1, UNSPECIFIED, 1), YcbcrReading.YCgCo)) {
      assertNull(source.nearestReadable(setOf(10, 13)), "$source")
      assertNotNull(source.nearestReadable(setOf(6, 10, 13))).matrix shouldBe 6
    }
  }

  @Test
  fun `a decoder reading the picture's own range lands it as the rangeless lookup does`() {
    for (source in listOf(ycbcrReading(1, 1, 1), ycbcrReading(6, 1, 1), ycbcrReading(10, 9, 14), YcbcrReading.YCgCo)) {
      for (fullRange in listOf(true, false)) {
        val readable = setOf(1, 6, 9)
        source.nearestReadable(readable, fullRange, fullRange) shouldBe source.nearestReadable(readable)
      }
    }
  }

  @Test
  fun `a decoder moving the range measures the move with the matrix`() {
    for ((fullRange, decoderFullRange) in listOf(true to false, false to true)) {
      val sameMatrix = assertNotNull(ycbcrReading(6, 1, 1).nearestReadable(setOf(6), fullRange, decoderFullRange))
      val moved = landingError(ycbcrReading(6, 1, 1), ycbcrReading(6, 1, 1), listOf(fullRange to decoderFullRange))
      sameMatrix shouldBe NearestMatrix(6, moved.meanDeltaE, moved.worstError, exact = false)
      assertTrue(sameMatrix.meanDeltaE > 1.0 && sameMatrix.worstError > 1.0, "$sameMatrix")
      val bt709 = ycbcrReading(1, 1, 1)
      val both = assertNotNull(bt709.nearestReadable(setOf(6), fullRange, decoderFullRange))
      val matrixOnly = assertNotNull(bt709.nearestReadable(setOf(6)))
      val via601 = ycbcrReading(6, UNSPECIFIED, UNSPECIFIED)
      val measured = landingError(bt709, via601, listOf(fullRange to decoderFullRange))
      both shouldBe NearestMatrix(6, measured.meanDeltaE, measured.worstError, exact = false)
      assertTrue(both.meanDeltaE > matrixOnly.meanDeltaE, "$both against $matrixOnly")
    }
  }

  // Full range samples read at limited range stretch away from mid grey, so a mid sample moves but stays inside the
  // worst channel error.
  @Test
  fun `a mid range sample read at the moved range lands within the worst error`() {
    val source = ycbcrReading(6, 1, 1)
    val nearest = assertNotNull(source.nearestReadable(setOf(6), fullRange = true, decoderFullRange = false))
    val own = ycbcrToRgb(source, MID_Y, MID_CB, MID_CR, 8, fullRange = true)
    val landed = ycbcrToRgb(source, MID_Y, MID_CB, MID_CR, 8, fullRange = false)
    val off = (0 until 3).maxOf { abs(own[it] - landed[it]) * 255 }
    assertTrue(off > 1.0 && off <= nearest.worstError, "$off codes against $nearest")
  }

  @Test
  fun `nothing readable or a refused reading gives no landing`() {
    assertNull(ycbcrReading(14, 9, 16).nearestReadable(setOf(1, 6, 9)))
    assertNull(ycbcrReading(9, 9, 1).nearestReadable(emptySet()))
    assertNull(ycbcrReading(9, 9, 1).nearestReadable(setOf(3, 11, 14, 12)))
  }

  private fun transferOf(reading: YcbcrReading): Int =
    (reading as? YcbcrReading.ConstantLuminance)?.oetf?.transfer ?: UNSPECIFIED

  private companion object {
    const val UNSPECIFIED = 2
    const val MID_Y = 110
    const val MID_CB = 140
    const val MID_CR = 150

    // Every reading but constant luminance on the two logarithmic curves, which hold light below their floor at zero
    // signal and so cannot bring it back.
    val READINGS: List<YcbcrReading> =
      listOf(0, 1, 4, 6, 7, 8, 9).map { ycbcrReading(it, UNSPECIFIED, 1) } +
        listOf(1, 4, 5, 7, 8, 13, 17).map { ycbcrReading(10, 9, it) } +
        ycbcrReading(12, 6, 1) +
        ycbcrReading(13, 12, 13)
  }
}
