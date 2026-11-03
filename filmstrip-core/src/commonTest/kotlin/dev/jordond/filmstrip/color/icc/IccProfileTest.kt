package dev.jordond.filmstrip.color.icc

import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.TestIccProfiles
import dev.jordond.filmstrip.color.TestIccProfiles.quantized
import dev.jordond.filmstrip.color.authoredHdrTexels
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.srgbToLinear
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test

class IccProfileTest {
  @Test
  fun `every para curve type evaluates the ICC formula through the middle of its range`() {
    PARA_CURVES.forEach { (type, params) ->
      val profile = curveOnly(TestIccProfiles.para(type, params))
      val stored = DoubleArray(params.size) { quantized(params[it]) }

      SAMPLES.forEach { x ->
        val got = profile.toPcs(x, x, x)
        val wanted = paraFormula(type, stored, x)
        got.forEach { assertNear(wanted, it, "para type $type at $x") }
      }
    }
  }

  @Test
  fun `a matrix profile reads its colorants as the red green and blue columns of its matrix`() {
    val colorants = TestIccProfiles.SRGB_COLORANTS
    val profile = parseIccProfile(TestIccProfiles.linearSrgb).shouldNotBeNull()
    val rgb = doubleArrayOf(0.8, 0.4, 0.2)
    val wanted =
      DoubleArray(3) { row ->
        var sum = 0.0
        for (channel in 0 until 3) sum += colorants[channel * 3 + row] / S15_FIXED_16_ONE * rgb[channel]
        sum
      }

    profile.toPcs(rgb[0], rgb[1], rgb[2]) shouldBe wanted
  }

  @Test
  fun `a curv table interpolates between its entries`() {
    val entries = intArrayOf(0, 9000, 30000, 65535)
    val profile = curveOnly(TestIccProfiles.curv(entries))

    SAMPLES.forEach { x ->
      val position = x * (entries.size - 1)
      val low = floor(position).toInt()
      val wanted = (entries[low] + (entries[low + 1] - entries[low]) * (position - low)) / 65535.0
      assertNear(wanted, profile.toPcs(x, x, x)[0], "curv table at $x")
    }
  }

  @Test
  fun `a curv gamma raises the channel to its stored power`() {
    val profile = curveOnly(TestIccProfiles.gammaCurv(1.8))

    SAMPLES.forEach { x -> assertNear(x.pow(461 / 256.0), profile.toPcs(x, x, x)[0], "curv gamma at $x") }
  }

  @Test
  fun `an mAB lookup table interpolates trilinearly between its grid points`() {
    val grid = intArrayOf(3, 4, 3)
    val tag =
      TestIccProfiles.mAB(
        aCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
        grid = grid,
        sample = ::bentSample,
        mCurves = null,
        matrix = null,
        bCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
      )
    val profile = parseIccProfile(TestIccProfiles.profile("RGB ", "XYZ ", listOf("A2B0" to tag))).shouldNotBeNull()
    val input = doubleArrayOf(0.6, 0.3, 0.85)

    val wanted = trilinear(grid, input).map { it * 65535 / 32768 }
    profile.toPcs(input[0], input[1], input[2]).forEachIndexed { channel, got ->
      assertNear(wanted[channel], got, "mAB channel $channel")
    }

    // The product terms are where trilinear and tetrahedral reads part, and the squared term is where any
    // interpolation parts from the sampled function, so the expected value is not one a shortcut reaches.
    val exact = bentSample(input[0], input[1], input[2])[1] * 65535 / 32768
    withClue("the table sample is too flat to tell interpolation apart") {
      (abs(exact - wanted[1]) > 1e-3).shouldBeTrue()
    }
  }

  @Test
  fun `device links and abstract profiles and profiles with no colorants do not parse`() {
    val link = TestIccProfiles.profile("RGB ", "XYZ ", emptyList()).also { "link".encodeInto(it, 12) }
    val abstract = TestIccProfiles.profile("RGB ", "XYZ ", emptyList()).also { "abst".encodeInto(it, 12) }
    val bare = TestIccProfiles.profile("RGB ", "XYZ ", emptyList())

    listOf(link, abstract, bare).forEach { parseIccProfile(it).shouldBeNull() }
  }

  @Test
  fun `a curv table is compared to sRGB between its entries as well as on them`() {
    withClue("a dense sRGB table should be sRGB") {
      parseIccProfile(TestIccProfiles.srgbTable).shouldNotBeNull().isSrgb.shouldBeTrue()
    }
    withClue("a sparse table read as sRGB") {
      parseIccProfile(TestIccProfiles.sparseSrgbTable).shouldNotBeNull().isSrgb.shouldBeFalse()
    }
  }

  @Test
  fun `a profile whose sizes run past its bytes reads as untagged`() {
    val hugeCurve = TestIccProfiles.curv(IntArray(0)).withU32(8, Int.MAX_VALUE)
    val lut8 = TestIccProfiles.legacyLut(wide = false) { _, _, _ -> intArrayOf(128, 128, 128) }
    val lut16 = TestIccProfiles.legacyLut(wide = true) { _, _, _ -> intArrayOf(0x8000, 0x8000, 0x8000) }
    val mab = mabTag()
    val clutAt = mab.u32(24)
    val hostile =
      listOf(
        "huge curv count" to TestIccProfiles.matrixTrc(TestIccProfiles.SRGB_COLORANTS, hugeCurve),
        "lut8 grid 255" to lutProfile(lut8.copyOf().also { it[10] = 255.toByte() }),
        "lut16 grid 255" to lutProfile(lut16.copyOf().also { it[10] = 255.toByte() }),
        "mAB grid 255" to lutProfile(mab.copyOf().also { tag -> (0 until 3).forEach { tag[clutAt + it] = -1 } }),
      )

    hostile.forEach { (name, profile) ->
      withClue(name) { parseIccProfile(profile).shouldBeNull() }
      assertUntagged(profile, name)
    }
  }

  @Test
  fun `a profile cut short anywhere reads as untagged or as the whole profile`() {
    listOf(TestIccProfiles.displayP3, TestIccProfiles.mab, lutProfile(mabTag())).forEach { profile ->
      val whole = pictureSrgbCodes(Icc(profile), COLORS)
      for (length in 0 until profile.size) {
        val cut = profile.copyOf(length)
        val codes = pictureSrgbCodes(Icc(cut), COLORS)
        withClue("cut to $length bytes") { (codes.contentEquals(COLORS) || codes.contentEquals(whole)).shouldBeTrue() }
      }
      assertUntagged(profile.copyOf(profile.size / 2), "cut in half")
    }
  }

  @Test
  fun `an unused tag that runs past the profile is skipped`() {
    val plain = TestIccProfiles.matrixTrc(TestIccProfiles.SRGB_COLORANTS, TestIccProfiles.gammaCurv(2.2))
    val text = listOf("desc" to ByteArray(12), "cprt" to ByteArray(12))
    val withText = TestIccProfiles.profile("RGB ", "XYZ ", matrixTags() + text)
    val pastEnd = withText.withTagEntry("desc", offset = 0x200, size = withText.size)
    val signBit = withText.withTagEntry("cprt", offset = Int.MIN_VALUE, size = 12)

    val wanted = pictureLight(Icc(plain), COLORS)
    listOf(pastEnd, signBit).forEach { pictureLight(Icc(it), COLORS) shouldBe wanted }
  }

  @Test
  fun `a needed tag that runs past the profile leaves it untagged`() {
    val profile = TestIccProfiles.matrixTrc(TestIccProfiles.SRGB_COLORANTS, TestIccProfiles.gammaCurv(2.2))

    assertUntagged(profile.withTagEntry("gTRC", offset = 0x100, size = profile.size), "gTRC past the end")
  }

  @Test
  fun `a lookup table that fails to read falls back to the next table and then to the matrix and curves`() {
    val broken = mabTag().also { it[0] = 'x'.code.toByte() }
    val matrixOnly = TestIccProfiles.matrixTrc(TestIccProfiles.SRGB_COLORANTS, TestIccProfiles.gammaCurv(2.2))
    val lutOnly = lutProfile(mabTag())

    val toMatrix = TestIccProfiles.profile("RGB ", "XYZ ", matrixTags() + listOf("A2B0" to broken))
    pictureLight(Icc(toMatrix), COLORS) shouldBe pictureLight(Icc(matrixOnly), COLORS)

    val toA2b0 =
      TestIccProfiles.profile("RGB ", "XYZ ", matrixTags() + listOf("A2B1" to broken, "A2B0" to mabTag()))
    pictureLight(Icc(toA2b0), COLORS) shouldBe pictureLight(Icc(lutOnly), COLORS)

    assertUntagged(TestIccProfiles.profile("RGB ", "XYZ ", listOf("A2B0" to broken)), "a broken table alone")
  }

  @Test
  fun `an A2B1 table wins over a different A2B0 table`() {
    val relative = mabTag()
    val perceptual = mabTag { r, g, b -> bentSample(r, g, b).map { it * 0.5 }.toDoubleArray() }
    val both = TestIccProfiles.profile("RGB ", "XYZ ", listOf("A2B0" to perceptual, "A2B1" to relative))

    val wanted = pictureLight(Icc(lutProfile(relative)), COLORS)
    val perceptualLight = pictureLight(Icc(lutProfile(perceptual)), COLORS)
    withClue("the two tables read the same") { wanted.contentEquals(perceptualLight).shouldBeFalse() }
    pictureLight(Icc(both), COLORS) shouldBe wanted
  }

  @Test
  fun `a table read from its stored codes gives exactly what its codes read as levels up front give`() {
    listOf(intArrayOf(2, 2, 2), intArrayOf(3, 4, 3), intArrayOf(3, 3, 3)).forEach { grid ->
      listOf(1, 2).forEach { width ->
        val top = if (width == 2) 65535 else 255
        val codes = gridCodes(grid, top)
        val clut = Clut(grid, IccSamples(stored(codes, width), width))
        val levels = DoubleArray(codes.size) { codes[it] / top.toDouble() }

        INPUTS.flatMap { r -> INPUTS.flatMap { g -> INPUTS.map { b -> doubleArrayOf(r, g, b) } } }.forEach { input ->
          val what = "grid ${grid.toList()} at ${input.toList()}"
          withClue(what) { clut.lookup(input) shouldBe levelLookup(grid, levels, input) }
        }
      }
    }

    listOf(
      intArrayOf(0, 9000, 30000, 65535) to 2,
      IntArray(16) { TestIccProfiles.sixteenBit(srgbToLinear(it / 15f).toDouble()) } to 2,
      IntArray(256) { 255 - it * it / 255 } to 1,
    ).forEach { (codes, width) ->
      val table = IccCurve.Table(IccSamples(stored(codes, width), width))
      val levels = DoubleArray(codes.size) { codes[it] / (if (width == 2) 65535.0 else 255.0) }
      (0..1000).forEach { step ->
        val x = step / 1000.0
        val position = x * (levels.size - 1)
        val low = position.toInt().coerceAtMost(levels.size - 2)
        val wanted = levels[low] + (levels[low + 1] - levels[low]) * (position - low)
        withClue("${codes.size} entries at $x") { table.evaluate(x) shouldBe wanted }
      }
    }
  }

  private fun assertUntagged(
    profile: ByteArray,
    what: String,
  ) {
    withClue(what) { pictureSrgbCodes(Icc(profile), COLORS) shouldBe COLORS }
    withClue(what) { pictureLight(Icc(profile), COLORS) shouldBe authoredHdrTexels(COLORS) }
  }

  private fun matrixTags(): List<Pair<String, ByteArray>> {
    val tags = mutableListOf<Pair<String, ByteArray>>()
    val whole = TestIccProfiles.matrixTrc(TestIccProfiles.SRGB_COLORANTS, TestIccProfiles.gammaCurv(2.2))
    listOf("rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC").forEach { name ->
      val (offset, size) = whole.tagEntry(name)
      tags += name to whole.copyOfRange(offset, offset + size)
    }
    return tags
  }

  private fun mabTag(sample: (Double, Double, Double) -> DoubleArray = ::bentSample): ByteArray =
    TestIccProfiles.mAB(
      aCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
      grid = intArrayOf(2, 2, 2),
      sample = sample,
      mCurves = null,
      matrix = null,
      bCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
    )

  private fun lutProfile(tag: ByteArray): ByteArray = TestIccProfiles.profile("RGB ", "XYZ ", listOf("A2B0" to tag))

  private fun ByteArray.tagEntry(name: String): Pair<Int, Int> {
    val at = tagEntryAt(name)
    return u32(at + 4) to u32(at + 8)
  }

  private fun ByteArray.withTagEntry(
    name: String,
    offset: Int,
    size: Int,
  ): ByteArray {
    val at = tagEntryAt(name)
    return withU32(at + 4, offset).withU32(at + 8, size)
  }

  private fun ByteArray.tagEntryAt(name: String): Int =
    (0 until u32(128)).map { 132 + it * 12 }.first { at -> (0 until 4).all { this[at + it] == name[it].code.toByte() } }

  private fun ByteArray.u32(at: Int): Int =
    (0 until 4).fold(0) { value, index -> (value shl 8) or (this[at + index].toInt() and 0xFF) }

  private fun ByteArray.withU32(
    at: Int,
    value: Int,
  ): ByteArray = copyOf().also { bytes -> (0 until 4).forEach { bytes[at + it] = (value shr (24 - it * 8)).toByte() } }

  private fun curveOnly(curve: ByteArray): IccProfile =
    parseIccProfile(TestIccProfiles.matrixTrc(IDENTITY_COLORANTS, curve)).shouldNotBeNull()

  private fun paraFormula(
    type: Int,
    p: DoubleArray,
    x: Double,
  ): Double =
    when (type) {
      0 -> x.pow(p[0])
      1 -> if (x >= -p[2] / p[1]) (p[1] * x + p[2]).pow(p[0]) else 0.0
      2 -> if (x >= -p[2] / p[1]) (p[1] * x + p[2]).pow(p[0]) + p[3] else p[3]
      3 -> if (x >= p[4]) (p[1] * x + p[2]).pow(p[0]) else p[3] * x
      else -> if (x >= p[4]) (p[1] * x + p[2]).pow(p[0]) + p[5] else p[3] * x + p[6]
    }

  private fun gridCodes(
    grid: IntArray,
    top: Int,
  ): IntArray {
    val codes = mutableListOf<Int>()
    for (r in 0 until grid[0]) {
      for (g in 0 until grid[1]) {
        for (b in 0 until grid[2]) {
          bentSample(r / (grid[0] - 1.0), g / (grid[1] - 1.0), b / (grid[2] - 1.0)).forEach {
            codes += (it * top).roundToInt()
          }
        }
      }
    }
    return codes.toIntArray()
  }

  private fun stored(
    codes: IntArray,
    width: Int,
  ): ByteArray =
    ByteArray(codes.size * width) { at ->
      if (width == 2) (codes[at / 2] shr (if (at % 2 == 0) 8 else 0)).toByte() else codes[at].toByte()
    }

  // The trilinear read over levels worked out from every code up front, the order Clut.lookup sums its corners in.
  private fun levelLookup(
    grid: IntArray,
    levels: DoubleArray,
    input: DoubleArray,
  ): DoubleArray {
    val low = IntArray(3)
    val weight = DoubleArray(3)
    for (axis in 0 until 3) {
      val position = input[axis].coerceIn(0.0, 1.0) * (grid[axis] - 1)
      low[axis] = position.toInt().coerceAtMost(grid[axis] - 2)
      weight[axis] = position - low[axis]
    }
    val out = DoubleArray(3)
    for (corner in 0 until 8) {
      var share = 1.0
      var index = 0
      for (axis in 0 until 3) {
        val high = (corner shr (2 - axis)) and 1
        share *= if (high == 1) weight[axis] else 1.0 - weight[axis]
        index = index * grid[axis] + (low[axis] + high).coerceAtMost(grid[axis] - 1)
      }
      if (share == 0.0) continue
      for (channel in 0 until 3) out[channel] += share * levels[index * 3 + channel]
    }
    return out
  }

  private fun trilinear(
    grid: IntArray,
    input: DoubleArray,
  ): List<Double> {
    val low = IntArray(3) { floor(input[it] * (grid[it] - 1)).toInt() }
    val weight = DoubleArray(3) { input[it] * (grid[it] - 1) - low[it] }
    val out = DoubleArray(3)
    for (dr in 0..1) {
      for (dg in 0..1) {
        for (db in 0..1) {
          val share =
            (if (dr == 1) weight[0] else 1 - weight[0]) *
              (if (dg == 1) weight[1] else 1 - weight[1]) *
              (if (db == 1) weight[2] else 1 - weight[2])
          val corner =
            bentSample(
              (low[0] + dr) / (grid[0] - 1.0),
              (low[1] + dg) / (grid[1] - 1.0),
              (low[2] + db) / (grid[2] - 1.0),
            )
          for (channel in 0 until 3) {
            out[channel] += share * TestIccProfiles.sixteenBit(corner[channel]) / 65535.0
          }
        }
      }
    }
    return out.toList()
  }

  private fun bentSample(
    r: Double,
    g: Double,
    b: Double,
  ): DoubleArray = doubleArrayOf(0.4 * r * g + 0.1 * b, 0.3 * g * b + 0.2 * r * r, 0.25 * r * g * b + 0.05)

  private fun String.encodeInto(
    bytes: ByteArray,
    at: Int,
  ) = forEachIndexed { index, char -> bytes[at + index] = char.code.toByte() }

  private fun assertNear(
    expected: Double,
    actual: Double,
    what: String,
  ) = withClue("$what: wanted $expected, got $actual") { (abs(expected - actual) <= 1e-6).shouldBeTrue() }

  private companion object {
    val SAMPLES = listOf(0.25, 0.5, 0.75)

    val INPUTS = (0..12).map { it / 12.0 } + listOf(0.37, 0.61, 0.93)

    val COLORS = intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF406080.toInt(), 0x80C04020.toInt())

    val IDENTITY_COLORANTS = intArrayOf(65536, 0, 0, 0, 65536, 0, 0, 0, 65536)

    // Each type's threshold sits inside the sampled range, so both branches of the piecewise ones run.
    val PARA_CURVES =
      listOf(
        0 to doubleArrayOf(2.2),
        1 to doubleArrayOf(2.0, 1.5, -0.45),
        2 to doubleArrayOf(1.8, 1.25, -0.4, 0.05),
        3 to doubleArrayOf(2.4, 0.9, 0.1, 0.2, 0.4),
        4 to doubleArrayOf(2.0, 0.8, 0.2, 0.15, 0.6, 0.01, 0.02),
      )
  }
}
