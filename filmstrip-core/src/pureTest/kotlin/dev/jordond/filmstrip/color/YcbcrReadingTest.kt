package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.media.CodecKind
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Checks each ITU-T H.273 matrix coefficients code point reads as Table 4 and section 8.3 say, and that a
 * chromaticity derived one takes Kr and Kb from its primaries.
 */
class YcbcrReadingTest {
  @Test
  fun `each named matrix reads with its Table 4 Kr and Kb`() {
    for ((matrix, coefficients) in TABLE_4) {
      ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED) shouldBe YcbcrReading.KrKb(coefficients.first, coefficients.second)
    }
    ycbcrReading(2, UNSPECIFIED, UNSPECIFIED) shouldBe ycbcrReading(6, UNSPECIFIED, UNSPECIFIED)
    ycbcrReading(0, 9, 16) shouldBe YcbcrReading.Identity
    ycbcrReading(8, 9, 16) shouldBe YcbcrReading.YCgCo
  }

  @Test
  fun `BT2020 constant luminance reads with BT2020 Kr and Kb and the curve its transfer names`() {
    val (kr, kb) = TABLE_4.getValue(9)
    for (transfer in listOf(1, 6, 14, 15, 2, 3)) {
      ycbcrReading(10, UNSPECIFIED, transfer) shouldBe YcbcrReading.ConstantLuminance(kr, kb, CicpOetf(1))
    }
    for (transfer in listOf(4, 5, 7, 8, 9, 10, 13, 17)) {
      ycbcrReading(10, UNSPECIFIED, transfer) shouldBe YcbcrReading.ConstantLuminance(kr, kb, CicpOetf(transfer))
    }
  }

  @Test
  fun `the derivation from primaries matches the closed form of H273 section 8_3`() {
    for (primaries in TABLE_PRIMARIES) {
      val reading = derivedKrKb(primaries).shouldNotBeNull()
      val (kr, kb) = closedForm(primaries)
      abs(reading.kr - kr) shouldBeBelow 1e-9
      abs(reading.kb - kb) shouldBeBelow 1e-9
    }
  }

  @Test
  fun `the derivation lands on BT709 and BT2020 coefficients for their own primaries`() {
    for ((primaries, matrix) in listOf(1 to 1, 9 to 9)) {
      val derived = derivedKrKb(primaries).shouldNotBeNull()
      val (kr, kb) = TABLE_4.getValue(matrix)
      abs(derived.kr - kr) shouldBeBelow TOLERANCE
      abs(derived.kb - kb) shouldBeBelow TOLERANCE
      ycbcrReading(12, primaries, 1) shouldBe ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED)
      ycbcrReading(12, primaries, 1).equivalentCode() shouldBe matrix
    }
    ycbcrReading(13, 9, 14) shouldBe ycbcrReading(10, UNSPECIFIED, 14)
    ycbcrReading(13, 9, 14).equivalentCode() shouldBe 10
  }

  @Test
  fun `a derived matrix no named matrix agrees with keeps its own Kr and Kb`() {
    for (primaries in TABLE_PRIMARIES) {
      val agreeing = TABLE_4.keys.filter { agrees(primaries, it) }
      val derived = derivedKrKb(primaries).shouldNotBeNull()
      if (agreeing.isEmpty()) {
        ycbcrReading(12, primaries, 1) shouldBe derived
        ycbcrReading(12, primaries, 1).equivalentCode() shouldBe null
        ycbcrReading(13, primaries, 1) shouldBe YcbcrReading.ConstantLuminance(derived.kr, derived.kb, CicpOetf(1))
        ycbcrReading(13, primaries, 1).equivalentCode() shouldBe null
      } else {
        (ycbcrReading(12, primaries, 1).equivalentCode() in agreeing).shouldBeTrue()
      }
    }
    for (primaries in listOf(5, 6, 7)) TABLE_4.keys.filter { agrees(primaries, it) } shouldBe emptyList()
  }

  @Test
  fun `constant luminance beside PQ or HLG is refused with its own reason`() {
    for (transfer in listOf(16, 18)) {
      ycbcrReading(10, 9, transfer) shouldBe YcbcrReading.Refused(HDR_CONSTANT_LUMINANCE_REASON)
      ycbcrReading(10, UNSPECIFIED, transfer) shouldBe YcbcrReading.Refused(HDR_CONSTANT_LUMINANCE_REASON)
      ycbcrReading(13, 9, transfer) shouldBe YcbcrReading.Refused(HDR_CONSTANT_LUMINANCE_REASON)
      ycbcrReading(13, 12, transfer) shouldBe YcbcrReading.Refused(HDR_CONSTANT_LUMINANCE_REASON)
      ycbcrReading(13, UNSPECIFIED, transfer) shouldBe YcbcrReading.Refused(UNDERIVED_MATRIX_REASON)
      ycbcrReading(9, 9, transfer) shouldBe ycbcrReading(9, UNSPECIFIED, UNSPECIFIED)
      ycbcrReading(12, 9, transfer) shouldBe ycbcrReading(9, UNSPECIFIED, UNSPECIFIED)
    }
    assertFailsWith<IllegalArgumentException> { cicpOetf(16) }
    assertFailsWith<IllegalArgumentException> { cicpOetf(18) }
  }

  @Test
  fun `the refused set reads with one reason each`() {
    ycbcrReading(11, 1, 1) shouldBe YcbcrReading.Refused(YDZDX_MATRIX_REASON)
    ycbcrReading(14, 9, 16) shouldBe YcbcrReading.Refused(ICTCP_MATRIX_REASON)
    for (matrix in listOf(3, 15, 16, 17, 255, 256, -1)) {
      val reading = ycbcrReading(matrix, 1, 1)
      reading.shouldBeInstanceOf<YcbcrReading.Refused>()
      withClue(reading.reason) { ("coefficients $matrix," in reading.reason).shouldBeTrue() }
    }
    for (primaries in listOf(2, 3, 255)) {
      ycbcrReading(12, primaries, 1) shouldBe YcbcrReading.Refused(UNDERIVED_MATRIX_REASON)
      ycbcrReading(13, primaries, 1) shouldBe YcbcrReading.Refused(UNDERIVED_MATRIX_REASON)
    }
    val read = (0..255).filter { ycbcrReading(it, 1, 1) !is YcbcrReading.Refused }
    read shouldBe listOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 12, 13)
  }

  @Test
  fun `each reading names the code point that reads the same`() {
    val named =
      listOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10).associateWith { ycbcrReading(it, UNSPECIFIED, 1).equivalentCode() }
    named shouldBe mapOf(0 to 0, 1 to 1, 2 to 6, 4 to 4, 5 to 6, 6 to 6, 7 to 7, 8 to 8, 9 to 9, 10 to 10)
    YcbcrReading.Refused(ICTCP_MATRIX_REASON).equivalentCode() shouldBe null
  }

  @Test
  fun `a coding reads its matrix beside its own primaries and transfer`() {
    val coding = YcbcrCoding(13, false, CodecKind.Av1, null, primaries = 9, transfer = 14)
    coding.reading shouldBe ycbcrReading(13, 9, 14)
    YcbcrCoding(12, true, CodecKind.Hevc, null, primaries = 1, transfer = 13).reading shouldBe
      ycbcrReading(1, UNSPECIFIED, UNSPECIFIED)
    YcbcrCoding(12, true, CodecKind.Hevc, null).reading shouldBe YcbcrReading.Refused(UNDERIVED_MATRIX_REASON)
    YcbcrCoding(2, true, CodecKind.Hevc, null).reading shouldBe ycbcrReading(6, UNSPECIFIED, UNSPECIFIED)
  }

  @Test
  fun `a coding is unsubsampled only on a 4_4_4 chroma format`() {
    val layouts = listOf(null, 0, 1, 2, 3).associateWith { coding(1, chromaFormat = it).unsubsampled }
    layouts shouldBe mapOf(null to false, 0 to false, 1 to false, 2 to false, 3 to true)
  }

  @Test
  fun `a coding refuses with core's reason before any backend limit`() {
    for (layout in listOf(null, 0, 1, 2)) {
      coding(0, chromaFormat = layout).refusal shouldBe IDENTITY_NOT_444_REASON
    }
    coding(0, bitDepth = null).refusal shouldBe IDENTITY_NOT_444_REASON
    coding(14, chromaFormat = 1, primaries = 9, transfer = 16).refusal shouldBe ICTCP_MATRIX_REASON
    coding(12, primaries = UNSPECIFIED).refusal shouldBe UNDERIVED_MATRIX_REASON
    coding(0, fullRange = false).refusal shouldBe null
    YcbcrCoding(0, true, CodecKind.Other, null).refusal shouldBe null
    for (matrix in listOf(1, 5, 8, 9, 10)) {
      coding(matrix, chromaFormat = 1).refusal shouldBe null
    }
  }

  // An HEVC coding at ten bits, 4:4:4 unless chromaFormat says otherwise.
  private fun coding(
    matrix: Int,
    fullRange: Boolean = true,
    bitDepth: Int? = 10,
    chromaFormat: Int? = 3,
    primaries: Int = 9,
    transfer: Int = 1,
  ) = YcbcrCoding(
    matrix,
    fullRange,
    CodecKind.Hevc,
    null,
    bitDepth = bitDepth,
    chromaFormat = chromaFormat,
    primaries = primaries,
    transfer = transfer,
  )

  // Kr and Kb from red, green, blue and white chromaticities, x then y, by the closed form of H.273 section 8.3.
  private fun closedForm(primaries: Int): Pair<Double, Double> {
    val c = cicpChromaticities(primaries).shouldNotBeNull()
    val (xr, yr, xg, yg) = listOf(c[0], c[1], c[2], c[3])
    val (xb, yb, xw, yw) = listOf(c[4], c[5], c[6], c[7])
    val (zr, zg, zb, zw) = listOf(1 - xr - yr, 1 - xg - yg, 1 - xb - yb, 1 - xw - yw)
    val denominator = yw * (xr * (yg * zb - yb * zg) + xg * (yb * zr - yr * zb) + xb * (yr * zg - yg * zr))
    val kr = yr * (xw * (yg * zb - yb * zg) + yw * (xb * zg - xg * zb) + zw * (xg * yb - xb * yg)) / denominator
    val kb = yb * (xw * (yr * zg - yg * zr) + yw * (xg * zr - xr * zg) + zw * (xr * yg - xg * yr)) / denominator
    return kr to kb
  }

  private fun agrees(
    primaries: Int,
    matrix: Int,
  ): Boolean {
    val (kr, kb) = closedForm(primaries)
    val (namedKr, namedKb) = TABLE_4.getValue(matrix)
    return abs(kr - namedKr) < TOLERANCE && abs(kb - namedKb) < TOLERANCE
  }

  private infix fun Double.shouldBeBelow(limit: Double) =
    withClue("$this is not below $limit") {
      (this < limit).shouldBeTrue()
    }

  private companion object {
    const val UNSPECIFIED = 2
    const val TOLERANCE = 0.0001
    val TABLE_PRIMARIES = listOf(1, 4, 5, 6, 7, 8, 9, 11, 12, 22)

    // Kr and Kb of each named matrix, as ITU-T H.273 Table 4 gives them.
    val TABLE_4 =
      mapOf(
        1 to (0.2126 to 0.0722),
        4 to (0.30 to 0.11),
        5 to (0.299 to 0.114),
        6 to (0.299 to 0.114),
        7 to (0.212 to 0.087),
        9 to (0.2627 to 0.0593),
      )
  }
}
