package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.picture.fixtures.cicpChunk
import dev.jordond.filmstrip.picture.fixtures.gamaChunk
import dev.jordond.filmstrip.picture.fixtures.heifItems
import dev.jordond.filmstrip.picture.fixtures.nclx
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.pictureColor
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Lands pictures described by CICP code points and PNG gamma and chromaticities, checking each against the H.273 and
 * PNG formulas worked out here at a mid value, where an additive and a multiplicative reading part ways.
 */
class PictureColorTest {
  @Test
  fun `png gamma one half squares a mid grey where sRGB does not`() {
    val half = pictureColor(png(gamaChunk(50000))).shouldNotBeNull()
    assertNear(pictureLight(half, intArrayOf(grey(128)))[0], (128 / 255.0).pow(2))
    assertNear(pictureLight(null, intArrayOf(grey(128)))[0], srgb(128 / 255.0))
    withClue("the two curves meet at a mid grey") {
      (abs((128 / 255.0).pow(2) - srgb(128 / 255.0)) > 0.03).shouldBeTrue()
    }
  }

  @Test
  fun `the BT 709 transfer reads through gamma 2 point 4 and parts from sRGB at a low code`() {
    val bt709 = pictureLight(Cicp(1, 1, 0, true), intArrayOf(grey(64)))[0]
    val srgb = pictureLight(Cicp(1, 13, 0, true), intArrayOf(grey(64)))[0]
    assertNear(bt709, (64 / 255.0).pow(2.4))
    assertNear(srgb, srgb(64 / 255.0))
    withClue("BT.709 read as sRGB") { (abs(bt709 - srgb) > 0.01f * HDR_REFERENCE_WHITE_NITS).shouldBeTrue() }
  }

  @Test
  fun `display p3 code points land a mid color in its P3 light`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { srgb(stored[it] / 255.0) }
    val wanted = toBt2020(P3 + D65, linear)
    val nits = pictureLight(Cicp(12, 13, 0, true), intArrayOf(color(stored)))
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel])
    val untagged = pictureLight(null, intArrayOf(color(stored)))
    withClue("P3 landed where sRGB does") { (0 until 3).any { abs(nits[it] - untagged[it]) > 1f }.shouldBeTrue() }
  }

  @Test
  fun `display p3 code points agree with a display p3 profile within one thousandth`() {
    val grid = gridColors()
    val cicp = pictureLight(Cicp(12, 13, 0, true), grid)
    val profile = pictureLight(Icc(TestIccProfiles.displayP3), grid)
    val worst = cicp.indices.filter { it % 4 != 3 }.maxOf { abs(cicp[it] - profile[it]) / HDR_REFERENCE_WHITE_NITS }
    withClue("Display P3 code points parted from the profile by $worst") { (worst <= 1e-3f).shouldBeTrue() }
  }

  @Test
  fun `sRGB code points and sRGB equivalent png gamma land exactly where an untagged picture does`() {
    val grid = gridColors()
    val untaggedLight = pictureLight(null, grid)
    val srgbGamma = listOf(45454, 45455, 45456).map { PngGamma(it, null) }
    val withRec709 = PngGamma(45455, REC709_CHROMATICITIES)
    val srgbCurve = PngGamma(null, REC709_CHROMATICITIES)
    for (color in listOf(Cicp(1, 13, 0, true), Cicp(1, 13, 6, false), withRec709, srgbCurve) + srgbGamma) {
      withClue("$color on HDR") { pictureLight(color, grid) shouldBe untaggedLight }
      withClue("$color on SDR") { pictureSrgbCodes(color, grid) shouldBe grid }
    }
  }

  @Test
  fun `png chromaticities alone land a mid color in their primaries under the sRGB curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { srgb(stored[it] / 255.0) }
    val nits = pictureLight(PngGamma(null, P3_CHROMATICITIES), intArrayOf(color(stored)))
    val wanted = toBt2020(P3 + D65, linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
  }

  @Test
  fun `png gamma alone lands a mid color in sRGB primaries under its own curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { (stored[it] / 255.0).pow(2) }
    val nits = pictureLight(PngGamma(50000, null), intArrayOf(color(stored)))
    val wanted = toBt2020(H273_PRIMARIES.getValue(1), linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
  }

  @Test
  fun `png gamma with chromaticities lands a mid color in their primaries under the gamma's curve`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { (stored[it] / 255.0).pow(100000 / 45455.0) }
    val nits = pictureLight(PngGamma(45455, P3_CHROMATICITIES), intArrayOf(color(stored)))
    val wanted = toBt2020(P3 + D65, linear)
    for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "channel $channel")
    val srgbCurve = pictureLight(PngGamma(null, P3_CHROMATICITIES), intArrayOf(color(stored)))
    withClue("gamma 45455 read as the sRGB curve") {
      (0 until 3).any { abs(nits[it] - srgbCurve[it]) > 0.5f }.shouldBeTrue()
    }
  }

  @Test
  fun `every supported primaries code point lands a mid color where H 273 puts it`() {
    val stored = intArrayOf(200, 100, 50)
    val linear = DoubleArray(3) { stored[it] / 255.0 }
    for ((code, chromaticities) in H273_PRIMARIES) {
      val nits = pictureLight(Cicp(code, 8, 0, true), intArrayOf(color(stored)))
      val wanted = toBt2020(chromaticities, linear)
      for (channel in 0 until 3) assertNear(nits[channel], wanted[channel], "primaries $code channel $channel")
    }
  }

  @Test
  fun `smpte st 428 primaries read their channels as XYZ under an equal energy white`() {
    val stored = intArrayOf(80, 128, 50)
    val xyz = DoubleArray(3) { stored[it] / 255.0 }
    val adapt = TestColorMath.bradford(doubleArrayOf(1.0, 1.0, 1.0), TestColorMath.D65)
    val wanted = TestColorMath.apply(xyzToBt2020(), TestColorMath.apply(adapt, xyz))
    val nits = pictureLight(Cicp(10, 8, 0, true), intArrayOf(color(stored)))
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
    withClue("ST 428 does not pass one at 252") { (curve(252 / 255.0) > 1.0).shouldBeTrue() }
    withClue("ST 428 passes one below 247") { (curve(246 / 255.0) < 1.0).shouldBeTrue() }
    for (level in listOf(246, 252, 255)) {
      val nits = pictureLight(Cicp(9, 17, 0, true), intArrayOf(grey(level)))
      for (channel in 0 until 3) assertNear(nits[channel], curve(level / 255.0).coerceAtMost(1.0), "level $level")
    }
  }

  @Test
  fun `the sRGB transfer reads a level below its knee on the linear segment`() {
    val level = 10
    withClue("level $level is not below the knee") { (level / 255.0 < 0.04045).shouldBeTrue() }
    val nits = pictureLight(Cicp(9, 13, 0, true), intArrayOf(grey(level)))
    for (channel in 0 until 3) assertNear(nits[channel], level / 255.0 / 12.92, "channel $channel")
  }

  @Test
  fun `the sRGB transfer reads every code exactly where srgbToLinear does`() {
    for (code in 0..255) SRGB_CURVE.evaluate(code / 255.0) shouldBe srgbToLinear(code / 255f).toDouble()
  }

  @Test
  fun `unspecified reserved and unsupported code points read as untagged`() {
    val grid = gridColors()
    val untagged = pictureLight(null, grid)
    val primaries = listOf(0, 2, 3, 13, 21, 23, 255).map { Cicp(it, 13, 0, true) }
    val transfers = listOf(0, 2, 3, 9, 10, 12, 19, 255).map { Cicp(9, it, 0, true) }
    for (color in primaries + transfers) {
      withClue("$color") { pictureLight(color, grid) shouldBe untagged }
      withClue("$color") { pictureSrgbCodes(color, grid) shouldBe grid }
    }
    pictureColor(png(cicpChunk(2, 13))).shouldBeNull()
    pictureColor(heifItems(listOf(nclx(9, 2)))).shouldBeNull()
  }

  @Test
  fun `an identity matrix at limited range keeps its primaries and transfer`() {
    val grid = gridColors()
    val limited = pictureColor(heifItems(listOf(nclx(12, 13, matrix = 0, fullRange = false))))
    limited shouldBe Cicp(12, 13, 0, false)
    val light = pictureLight(limited, grid)
    light shouldBe pictureLight(Cicp(12, 13, 0, true), grid)
    withClue("limited range P3 landed as untagged") { (!light.contentEquals(pictureLight(null, grid))).shouldBeTrue() }
    pictureColor(heifItems(listOf(nclx(1, 13, matrix = 0, fullRange = false)))).shouldBeNull()
    val srgb = Cicp(1, 13, 0, false)
    pictureLight(srgb, grid) shouldBe pictureLight(null, grid)
    pictureSrgbCodes(srgb, grid) shouldBe grid
  }

  @Test
  fun `pq and hlg code points are read and land apart from an untagged picture`() {
    val grid = gridColors()
    for (transfer in listOf(16, 18)) {
      withClue("transfer $transfer png") {
        pictureColor(png(cicpChunk(9, transfer))) shouldBe Cicp(9, transfer, 0, true)
      }
      withClue("$transfer heif") {
        pictureColor(heifItems(listOf(nclx(9, transfer)))) shouldBe Cicp(9, transfer, 6, true)
      }
      val light = pictureLight(Cicp(9, transfer, 0, true), grid)
      withClue("transfer $transfer landed as untagged") {
        (!light.contentEquals(pictureLight(null, grid))).shouldBeTrue()
      }
    }
  }

  private fun toBt2020(
    chromaticities: DoubleArray,
    linear: DoubleArray,
  ): DoubleArray {
    val white = doubleArrayOf(chromaticities[6], chromaticities[7])
    val whiteXyz = doubleArrayOf(white[0] / white[1], 1.0, (1 - white[0] - white[1]) / white[1])
    val toXyz = rgbToXyzUnder(chromaticities, whiteXyz)
    val adapt = TestColorMath.bradford(whiteXyz, TestColorMath.D65)
    val xyz = TestColorMath.apply(adapt, TestColorMath.apply(toXyz, linear))
    return TestColorMath.apply(xyzToBt2020(), xyz).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
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
    val scale = TestColorMath.apply(TestColorMath.invert(columns), white)
    return DoubleArray(9) { columns[it] * scale[it % 3] }
  }

  private fun xyzToBt2020(): DoubleArray =
    TestColorMath.invert(TestColorMath.rgbToXyz(TestIccProfiles.BT2020_PRIMARIES))

  private fun assertNear(
    nits: Float,
    light: Double,
    what: String = "",
  ) {
    val off = abs(nits / HDR_REFERENCE_WHITE_NITS - light)
    withClue("$what read ${nits / HDR_REFERENCE_WHITE_NITS}, wanted $light") { (off <= TOLERANCE).shouldBeTrue() }
  }

  private fun gridColors(steps: Int = 9): IntArray {
    val levels = (0 until steps).map { (it * 255.0 / (steps - 1)).roundToInt() }
    return levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> color(intArrayOf(r, g, b)) } } }.toIntArray()
  }

  private fun grey(level: Int): Int = color(intArrayOf(level, level, level))

  private fun color(rgb: IntArray): Int = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]

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
