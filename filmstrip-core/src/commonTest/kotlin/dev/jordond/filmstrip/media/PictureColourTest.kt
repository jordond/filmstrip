package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.cicpChunk
import dev.jordond.filmstrip.internal.gamaChunk
import dev.jordond.filmstrip.internal.heifItems
import dev.jordond.filmstrip.internal.nclx
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.png
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lands pictures described by CICP code points and PNG gamma and chromaticities, checking each against the H.273 and
 * PNG formulas worked out here at a mid value, where an additive and a multiplicative reading part ways.
 */
class PictureColourTest {
  @Test
  fun `png gamma one half squares a mid grey where sRGB does not`() {
    val half = assertNotNull(pictureColour(png(gamaChunk(50000))))
    assertNear(pictureLight(half, intArrayOf(grey(128)))[0], (128 / 255.0).pow(2))
    assertNear(pictureLight(null, intArrayOf(grey(128)))[0], srgb(128 / 255.0))
    assertTrue(abs((128 / 255.0).pow(2) - srgb(128 / 255.0)) > 0.03, "the two curves meet at a mid grey")
  }

  @Test
  fun `the BT 709 transfer reads through gamma 2 point 4 and parts from sRGB at a low code`() {
    val bt709 = pictureLight(Cicp(1, 1, 0, true), intArrayOf(grey(64)))[0]
    val srgb = pictureLight(Cicp(1, 13, 0, true), intArrayOf(grey(64)))[0]
    assertNear(bt709, (64 / 255.0).pow(2.4))
    assertNear(srgb, srgb(64 / 255.0))
    assertTrue(abs(bt709 - srgb) > 0.01f * HDR_REFERENCE_WHITE_NITS, "BT.709 read as sRGB")
  }

  @Test
  fun `display p3 code points land a mid colour in its P3 light`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { srgb(stored[it] / 255.0) }
    val wanted = toBt2020(P3 + D65, linear)
    val nits = pictureLight(Cicp(12, 13, 0, true), intArrayOf(colour(stored)))
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel])
    val untagged = pictureLight(null, intArrayOf(colour(stored)))
    assertTrue((0 until 3).any { abs(nits[it] - untagged[it]) > 1f }, "P3 landed where sRGB does")
  }

  @Test
  fun `display p3 code points agree with a display p3 profile within one thousandth`() {
    val grid = gridColours()
    val cicp = pictureLight(Cicp(12, 13, 0, true), grid)
    val profile = pictureLight(Icc(TestIccProfiles.displayP3), grid)
    val worst = cicp.indices.filter { it % 4 != 3 }.maxOf { abs(cicp[it] - profile[it]) / HDR_REFERENCE_WHITE_NITS }
    assertTrue(worst <= 1e-3f, "Display P3 code points parted from the profile by $worst")
  }

  @Test
  fun `sRGB code points and sRGB equivalent png gamma land exactly where an untagged picture does`() {
    val grid = gridColours()
    val untaggedLight = pictureLight(null, grid)
    val srgbGamma = listOf(45454, 45455, 45456).map { PngGamma(it, null) }
    val withRec709 = PngGamma(45455, REC709_CHROMATICITIES)
    val srgbCurve = PngGamma(null, REC709_CHROMATICITIES)
    for (colour in listOf(Cicp(1, 13, 0, true), Cicp(1, 13, 6, false), withRec709, srgbCurve) + srgbGamma) {
      assertContentEquals(untaggedLight, pictureLight(colour, grid), "$colour on HDR")
      assertContentEquals(grid, pictureSrgbCodes(colour, grid), "$colour on SDR")
    }
  }

  @Test
  fun `png chromaticities alone land a mid colour in their primaries under the sRGB curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { srgb(stored[it] / 255.0) }
    val nits = pictureLight(PngGamma(null, P3_CHROMATICITIES), intArrayOf(colour(stored)))
    val wanted = toBt2020(P3 + D65, linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
  }

  @Test
  fun `png gamma alone lands a mid colour in sRGB primaries under its own curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { (stored[it] / 255.0).pow(2) }
    val nits = pictureLight(PngGamma(50000, null), intArrayOf(colour(stored)))
    val wanted = toBt2020(H273_PRIMARIES.getValue(1), linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
  }

  @Test
  fun `png gamma with chromaticities lands a mid colour in their primaries under the gamma's curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { (stored[it] / 255.0).pow(100000 / 45455.0) }
    val nits = pictureLight(PngGamma(45455, P3_CHROMATICITIES), intArrayOf(colour(stored)))
    val wanted = toBt2020(P3 + D65, linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
    val srgbCurve = pictureLight(PngGamma(null, P3_CHROMATICITIES), intArrayOf(colour(stored)))
    assertTrue((0 until 3).any { abs(nits[it] - srgbCurve[it]) > 0.5f }, "gamma 45455 read as the sRGB curve")
  }

  @Test
  fun `every supported primaries code point lands a mid colour where H 273 puts it`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { stored[it] / 255.0 }
    for ((code, chromaticities) in H273_PRIMARIES) {
      val nits = pictureLight(Cicp(code, 8, 0, true), intArrayOf(colour(stored)))
      val wanted = toBt2020(chromaticities, linear)
      for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "primaries $code channel $channel")
    }
  }

  @Test
  fun `smpte st 428 primaries read their channels as XYZ under an equal energy white`() {
    val stored = intArrayOf(80, 128, 50)
    val xyz = DoubleArray(3) { stored[it] / 255.0 }
    val adapt = TestColourMath.bradford(doubleArrayOf(1.0, 1.0, 1.0), TestColourMath.D65)
    val wanted = TestColourMath.apply(xyzToBt2020(), TestColourMath.apply(adapt, xyz))
    val nits = pictureLight(Cicp(10, 8, 0, true), intArrayOf(colour(stored)))
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel].coerceIn(0.0, 1.0), "channel $channel")
  }

  @Test
  fun `every supported transfer code point reads a low and a mid grey the way H 273 defines it`() {
    for ((code, curve) in H273_TRANSFERS) {
      for (level in listOf(16, 128)) {
        val nits = pictureLight(Cicp(9, code, 0, true), intArrayOf(grey(level)))
        val wanted = curve(level / 255.0).coerceIn(0.0, 1.0)
        for (channel in 0 until 3) assertNear(nits[channel], wanted, "transfer $code at $level")
      }
    }
  }

  @Test
  fun `the st 428 curve clips near full level where its formula passes one`() {
    val curve = H273_TRANSFERS.getValue(17)
    assertTrue(curve(252 / 255.0) > 1.0, "ST 428 does not pass one at 252")
    assertTrue(curve(246 / 255.0) < 1.0, "ST 428 passes one below 247")
    for (level in listOf(246, 252, 255)) {
      val nits = pictureLight(Cicp(9, 17, 0, true), intArrayOf(grey(level)))
      for (channel in 0 until 3) assertNear(nits[channel], curve(level / 255.0).coerceAtMost(1.0), "level $level")
    }
  }

  @Test
  fun `the sRGB transfer reads a level below its knee on the linear segment`() {
    val level = 10
    assertTrue(level / 255.0 < 0.04045, "level $level is not below the knee")
    val nits = pictureLight(Cicp(9, 13, 0, true), intArrayOf(grey(level)))
    for (channel in 0 until 3) assertNear(nits[channel], level / 255.0 / 12.92, "channel $channel")
  }

  @Test
  fun `the sRGB transfer reads every code exactly where srgbToLinear does`() {
    for (code in 0..255) assertEquals(srgbToLinear(code / 255f).toDouble(), SRGB_CURVE.evaluate(code / 255.0))
  }

  @Test
  fun `unspecified reserved and unsupported code points read as untagged`() {
    val grid = gridColours()
    val untagged = pictureLight(null, grid)
    val primaries = listOf(0, 2, 3, 13, 21, 23, 255).map { Cicp(it, 13, 0, true) }
    val transfers = listOf(0, 2, 3, 9, 10, 12, 19, 255).map { Cicp(9, it, 0, true) }
    for (colour in primaries + transfers) {
      assertContentEquals(untagged, pictureLight(colour, grid), "$colour")
      assertContentEquals(grid, pictureSrgbCodes(colour, grid), "$colour")
    }
    assertNull(pictureColour(png(cicpChunk(2, 13))))
    assertNull(pictureColour(heifItems(listOf(nclx(9, 2)))))
  }

  @Test
  fun `an identity matrix at limited range keeps its primaries and transfer`() {
    val grid = gridColours()
    val limited = pictureColour(heifItems(listOf(nclx(12, 13, matrix = 0, fullRange = false))))
    assertEquals(Cicp(12, 13, 0, false), limited)
    val light = pictureLight(limited, grid)
    assertContentEquals(pictureLight(Cicp(12, 13, 0, true), grid), light)
    assertTrue(!light.contentEquals(pictureLight(null, grid)), "limited range P3 landed as untagged")
    assertNull(pictureColour(heifItems(listOf(nclx(1, 13, matrix = 0, fullRange = false)))))
    val srgb = Cicp(1, 13, 0, false)
    assertContentEquals(pictureLight(null, grid), pictureLight(srgb, grid))
    assertContentEquals(grid, pictureSrgbCodes(srgb, grid))
  }

  @Test
  fun `pq and hlg code points are read and land apart from an untagged picture`() {
    val grid = gridColours()
    for (transfer in listOf(16, 18)) {
      assertEquals(Cicp(9, transfer, 0, true), pictureColour(png(cicpChunk(9, transfer))), "transfer $transfer png")
      assertEquals(Cicp(9, transfer, 6, true), pictureColour(heifItems(listOf(nclx(9, transfer)))), "$transfer heif")
      val light = pictureLight(Cicp(9, transfer, 0, true), grid)
      assertTrue(!light.contentEquals(pictureLight(null, grid)), "transfer $transfer landed as untagged")
    }
  }

  private fun toBt2020(
    chromaticities: DoubleArray,
    linear: DoubleArray,
  ): DoubleArray {
    val white = doubleArrayOf(chromaticities[6], chromaticities[7])
    val whiteXyz = doubleArrayOf(white[0] / white[1], 1.0, (1 - white[0] - white[1]) / white[1])
    val toXyz = rgbToXyzUnder(chromaticities, whiteXyz)
    val adapt = TestColourMath.bradford(whiteXyz, TestColourMath.D65)
    val xyz = TestColourMath.apply(adapt, TestColourMath.apply(toXyz, linear))
    return TestColourMath.apply(xyzToBt2020(), xyz).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
  }

  private fun rgbToXyzUnder(
    chromaticities: DoubleArray,
    white: DoubleArray,
  ): DoubleArray {
    val columns = DoubleArray(9)
    for (p in 0 until 3) {
      val x = chromaticities[p * 2]
      val y = chromaticities[p * 2 + 1]
      columns[p] = x / y
      columns[3 + p] = 1.0
      columns[6 + p] = (1 - x - y) / y
    }
    val scale = TestColourMath.apply(TestColourMath.invert(columns), white)
    return DoubleArray(9) { columns[it] * scale[it % 3] }
  }

  private fun xyzToBt2020(): DoubleArray =
    TestColourMath.invert(TestColourMath.rgbToXyz(TestIccProfiles.BT2020_PRIMARIES))

  private fun assertNear(
    nits: Float,
    light: Double,
    what: String = "",
  ) {
    val off = abs(nits / HDR_REFERENCE_WHITE_NITS - light)
    assertTrue(off <= TOLERANCE, "$what read ${nits / HDR_REFERENCE_WHITE_NITS}, wanted $light")
  }

  private fun gridColours(steps: Int = 9): IntArray {
    val levels = (0 until steps).map { (it * 255.0 / (steps - 1)).roundToInt() }
    return levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> colour(intArrayOf(r, g, b)) } } }.toIntArray()
  }

  private fun grey(level: Int): Int = colour(intArrayOf(level, level, level))

  private fun colour(rgb: IntArray): Int = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]

  private companion object {
    const val TOLERANCE = 1e-5

    val D65 = doubleArrayOf(0.3127, 0.3290)
    val C = doubleArrayOf(0.310, 0.316)
    val P3 = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)
    val P3_CHROMATICITIES = PngChromaticities(31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000)

    fun srgb(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    // Rec. ITU-T H.273 table 2, red, green, blue and white, x then y. Primaries 10 is XYZ itself, checked on its own.
    val H273_PRIMARIES =
      mapOf(
        1 to doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060) + D65,
        4 to doubleArrayOf(0.67, 0.33, 0.21, 0.71, 0.14, 0.08) + C,
        5 to doubleArrayOf(0.64, 0.33, 0.29, 0.60, 0.15, 0.06) + D65,
        6 to doubleArrayOf(0.630, 0.340, 0.310, 0.595, 0.155, 0.070) + D65,
        7 to doubleArrayOf(0.630, 0.340, 0.310, 0.595, 0.155, 0.070) + D65,
        8 to doubleArrayOf(0.681, 0.319, 0.243, 0.692, 0.145, 0.049) + C,
        9 to doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046) + D65,
        11 to P3 + doubleArrayOf(0.314, 0.351),
        12 to P3 + D65,
        22 to doubleArrayOf(0.630, 0.340, 0.295, 0.605, 0.155, 0.077) + D65,
      )

    // Rec. ITU-T H.273 table 3, each curve taking a stored level to linear light. The BT.709 family reads through
    // BT.1886's display gamma, and SMPTE 240M and ST 428 invert their H.273 formulas.
    val H273_TRANSFERS: Map<Int, (Double) -> Double> =
      mapOf(
        1 to { v -> v.pow(2.4) },
        4 to { v -> v.pow(2.2) },
        5 to { v -> v.pow(2.8) },
        6 to { v -> v.pow(2.4) },
        7 to { v -> if (v < 4 * 0.0228) v / 4 else ((v + 0.1115) / 1.1115).pow(1 / 0.45) },
        8 to { v -> v },
        11 to { v -> v.pow(2.4) },
        13 to ::srgb,
        14 to { v -> v.pow(2.4) },
        15 to { v -> v.pow(2.4) },
        17 to { v -> v.pow(2.6) * 52.37 / 48 },
      )
  }
}
