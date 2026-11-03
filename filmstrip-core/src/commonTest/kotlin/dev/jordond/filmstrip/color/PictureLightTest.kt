package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.color.TestIccProfiles.BT2020_PRIMARIES
import dev.jordond.filmstrip.color.TestIccProfiles.BT709_PRIMARIES
import dev.jordond.filmstrip.color.TestIccProfiles.P3_PRIMARIES
import dev.jordond.filmstrip.color.icc.IccCurve
import dev.jordond.filmstrip.color.icc.IccProfile
import dev.jordond.filmstrip.color.icc.parseIccProfile
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test

class PictureLightTest {
  private val texels = intArrayOf(BLACK, MID_GREY, WHITE, RED, HALF_ALPHA_TEAL, OFF_GREY)

  @Test
  fun `an untagged picture lands on its stored codes and on authored light`() {
    pictureSrgbCodes(null, texels) shouldBe texels
    pictureLight(null, texels) shouldBe authoredHdrTexels(texels)
  }

  @Test
  fun `an sRGB tagged picture lands exactly where an untagged one does`() {
    listOf(TestIccProfiles.srgb, TestIccProfiles.srgbTable).forEach { profile ->
      withClue("an sRGB profile did not read as sRGB") {
        parseIccProfile(profile).shouldNotBeNull().isSrgb.shouldBeTrue()
      }
      pictureSrgbCodes(Icc(profile), texels) shouldBe texels
      pictureLight(Icc(profile), texels) shouldBe authoredHdrTexels(texels)
    }
  }

  @Test
  fun `a profile that is not an RGB or grey profile reads as untagged`() {
    val cmyk = TestIccProfiles.profile("CMYK", "Lab ", emptyList())
    val garbage = ByteArray(200) { (it * 7).toByte() }

    listOf(cmyk, garbage).forEach { profile ->
      pictureSrgbCodes(Icc(profile), texels) shouldBe texels
      pictureLight(Icc(profile), texels) shouldBe authoredHdrTexels(texels)
    }
  }

  @Test
  fun `a gamma 2 point 2 curve and a nudged primary do not count as sRGB`() {
    listOf(TestIccProfiles.gamma22, TestIccProfiles.nudgedSrgb).forEach { profile ->
      withClue("a near sRGB profile read as sRGB") { parseIccProfile(profile).shouldNotBeNull().isSrgb.shouldBeFalse() }
    }

    // A grey keeps the profile's white chromaticity, so it lands on the stored gamma's light in every channel.
    val gamma = pictureLight(Icc(TestIccProfiles.gamma22), intArrayOf(MID_GREY))
    val wanted = (HDR_REFERENCE_WHITE_NITS * (128 / 255.0).pow(563 / 256.0)).toFloat()
    (0 until 3).forEach { gamma[it] shouldBeNear wanted }
    withClue("gamma 2.2 read as sRGB") {
      (abs(gamma[1] - authoredHdrTexels(intArrayOf(MID_GREY))[1]) > NITS_TOLERANCE).shouldBeTrue()
    }
  }

  @Test
  fun `a linear light picture lands its mid code on that much light`() {
    val light = 128 / 255.0
    val code = (linearToSrgb(light.toFloat()) * 255f).roundToInt()

    pictureSrgbCodes(Icc(TestIccProfiles.linearSrgb), intArrayOf(MID_GREY))[0] shouldBe argb(code, code, code)

    val nits = pictureLight(Icc(TestIccProfiles.linearSrgb), intArrayOf(MID_GREY))
    val expected = FloatArray(3) { (light * HDR_REFERENCE_WHITE_NITS).toFloat() }
    HdrTransfer.entries.forEach { transfer ->
      withClue("$transfer") { codes(nits.copyOfRange(0, 3), transfer) shouldBe codes(expected, transfer) }
    }
  }

  @Test
  fun `display P3 green keeps its full gamut on HDR`() {
    val expected = p3InBt2020(column = 1)
    withClue("P3 green should sit inside BT.2020") { expected.all { it > 0.0 }.shouldBeTrue() }

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(argb(0, 255, 0)))
    val wanted = FloatArray(3) { (expected[it] * HDR_REFERENCE_WHITE_NITS).toFloat() }
    (0 until 3).forEach { nits[it] shouldBeNear wanted[it] }
    HdrTransfer.entries.forEach { transfer ->
      withClue("$transfer") { codes(nits.copyOfRange(0, 3), transfer) shouldBe codes(wanted, transfer) }
    }
  }

  @Test
  fun `display P3 red reaches past BT 709 red on HDR and clips to it on SDR`() {
    val expected = p3InBt2020(column = 0)
    // P3's red sits a hair past BT.2020's red to green edge, so only blue leaves BT.2020 and clips.
    withClue("P3 red should sit just outside BT.2020 on blue") { (expected[2] < 0.0).shouldBeTrue() }

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(RED))
    val wanted = FloatArray(3) { (expected[it].coerceAtLeast(0.0) * HDR_REFERENCE_WHITE_NITS).toFloat() }
    (0 until 3).forEach { nits[it] shouldBeNear wanted[it] }
    withClue("P3 red landed no further out than BT.709 red") { (nits[0] > authoredHdrNits(RED)[0]).shouldBeTrue() }
    HdrTransfer.entries.forEach { transfer ->
      withClue("$transfer") { codes(nits.copyOfRange(0, 3), transfer) shouldBe codes(wanted, transfer) }
    }

    pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(RED))[0] shouldBe RED
  }

  @Test
  fun `a display P3 mid grey lands on an sRGB mid grey`() {
    pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(MID_GREY))[0] shouldBe MID_GREY

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(MID_GREY))
    val authored = authoredHdrTexels(intArrayOf(MID_GREY))
    HdrTransfer.entries.forEach { transfer ->
      codes(nits.copyOfRange(0, 3), transfer) shouldBe codes(authored.copyOfRange(0, 3), transfer)
    }
  }

  @Test
  fun `alpha carries through a tagged picture unchanged`() {
    val codes = pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(HALF_ALPHA_TEAL))
    (codes[0] ushr 24) shouldBe (HALF_ALPHA_TEAL ushr 24)

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(HALF_ALPHA_TEAL))
    nits[3] shouldBe authoredHdrTexels(intArrayOf(HALF_ALPHA_TEAL))[3]
  }

  @Test
  fun `a grey profile lifts its level through the grey curve onto white`() {
    val gamma = 563 / 256.0
    val nits = pictureLight(Icc(TestIccProfiles.grey22), intArrayOf(MID_GREY))

    (0 until 3).forEach { nits[it] shouldBeNear (HDR_REFERENCE_WHITE_NITS * (128 / 255.0).pow(gamma)).toFloat() }
  }

  @Test
  fun `a mid saturation display P3 color lands on its BT 709 codes on SDR`() {
    val stored = intArrayOf(200, 120, 90)
    val p3ToBt709 =
      TestColorMath.multiply(
        TestColorMath.invert(TestColorMath.rgbToXyz(BT709_PRIMARIES)),
        TestColorMath.rgbToXyz(P3_PRIMARIES),
      )
    val light = TestColorMath.apply(p3ToBt709, DoubleArray(3) { srgbToLinear(stored[it] / 255f).toDouble() })
    withClue("the color should sit inside BT.709") { light.all { it > 0.0 && it < 1.0 }.shouldBeTrue() }
    val wanted = light.map { (linearToSrgb(it.toFloat()) * 255).roundToInt() }

    val codes = pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(argb(stored[0], stored[1], stored[2])))[0]

    codes shouldBe argb(wanted[0], wanted[1], wanted[2])
    withClue("the color is too grey to tell P3 from BT.709") { (wanted != stored.toList()).shouldBeTrue() }
  }

  @Test
  fun `lut8 lut16 and mAB tables decode their Lab encodings off the grey axis`() {
    // Each table holds the same lightness and a and b codes, linear in red, green and blue, in its own encoding.
    // lut8 stores the eight bit code, legacy lut16 that code times 256 and mAB that code times 257.
    fun lab(
      r: Double,
      g: Double,
      b: Double,
    ) = doubleArrayOf(60 + 120 * r + 40 * g, 128 + 90 * g - 50 * r, 128 - 100 * b + 20 * r)

    val lut8 = TestIccProfiles.legacyLut(wide = false) { r, g, b -> lab(r * 1.0, g * 1.0, b * 1.0).toCodes(1) }
    val lut16 = TestIccProfiles.legacyLut(wide = true) { r, g, b -> lab(r * 1.0, g * 1.0, b * 1.0).toCodes(256) }
    val mab =
      TestIccProfiles.mAB(
        aCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
        grid = intArrayOf(2, 2, 2),
        sample = { r, g, b -> lab(r, g, b).map { it / 255 }.toDoubleArray() },
        mCurves = null,
        matrix = null,
        bCurves = List(3) { TestIccProfiles.curv(IntArray(0)) },
      )
    val stored = intArrayOf(102, 153, 204)
    val codes = lab(stored[0] / 255.0, stored[1] / 255.0, stored[2] / 255.0)
    val lightness = codes[0] / 255 * 100
    val a = codes[1] - 128
    val b = codes[2] - 128
    withClue("the color should sit well off the grey axis") { (a > 20 && b < -20).shouldBeTrue() }
    val wanted = TestColorMath.labToXyz(lightness, a, b)

    listOf("lut8" to lut8, "lut16" to lut16, "mAB" to mab).forEach { (name, tag) ->
      val profile = TestIccProfiles.profile("RGB ", "Lab ", listOf("A2B0" to tag))
      val pcs =
        parseIccProfile(
          profile,
        ).shouldNotBeNull().toPcs(stored[0] / 255.0, stored[1] / 255.0, stored[2] / 255.0)
      (0 until 3).forEach {
        withClue("$name axis $it read ${pcs[it]}") {
          (abs(pcs[it] - wanted[it]) <= 1e-6).shouldBeTrue()
        }
      }

      val nits = pictureLight(Icc(profile), intArrayOf(argb(stored[0], stored[1], stored[2])))
      val light = TestColorMath.apply(pcsToBt2020(), wanted).map { it.coerceIn(0.0, 1.0) }
      (0 until 3).forEach { nits[it] shouldBeNear (light[it] * HDR_REFERENCE_WHITE_NITS).toFloat() }
    }
  }

  @Test
  fun `a Bradford sRGB profile lands on authored light within its colorant rounding`() {
    withClue("Bradford sRGB read as sRGB") {
      parseIccProfile(TestIccProfiles.bradfordSrgb).shouldNotBeNull().isSrgb.shouldBeFalse()
    }
    // Each colorant is stored to the nearest s15Fixed16 step, half of 1/65536 off, and a channel sums three of them
    // through the BT.2020 matrix. The stored para curve sits up to one sixteen-bit step off the sRGB curve.
    val rowSum = (0 until 3).maxOf { row -> (0 until 3).sumOf { abs(PCS_TO_BT2020[row * 3 + it]) } }
    val colorantRounding = (HDR_REFERENCE_WHITE_NITS * (rowSum * 3 * 0.5 / 65536 + 1.0 / 65535)).toFloat()

    val grid = gridColors()
    val nits = pictureLight(Icc(TestIccProfiles.bradfordSrgb), grid)
    val authored = authoredHdrTexels(grid)

    val worst = nits.indices.filter { it % 4 != 3 }.maxOf { abs(nits[it] - authored[it]) }
    withClue("off by $worst nits, over $colorantRounding") { (worst <= colorantRounding).shouldBeTrue() }
  }

  @Test
  fun `a picture gets its texel count in color slots rounded up to a power of two and at most 16384`() {
    ColorSlots(1).size shouldBe 2
    ColorSlots(3).size shouldBe 4
    ColorSlots(1000).size shouldBe 1024
    ColorSlots(1 shl 14).size shouldBe (1 shl 14)
    ColorSlots(1 shl 20).size shouldBe (1 shl 14)
  }

  @Test
  fun `wide slots tell colors apart by their low key and never read the high key of all ones as filled`() {
    val slots = ColorSlots(4, wide = true)
    val allOnes = (65535 shl 16) or 65535
    withClue("an empty slot read as red and green 65535") { slots.claim(0, allOnes, 65535).shouldBeTrue() }
    withClue("a filled slot missed its own color") { slots.claim(0, allOnes, 65535).shouldBeFalse() }
    (slots.slotOf(allOnes, 65535) in 0 until slots.size).shouldBeTrue()

    val high = (1200 shl 16) or 34000
    slots.claim(1, high, 5).shouldBeTrue()
    withClue("a second low under the same high key read as the first") { slots.claim(1, high, 6).shouldBeTrue() }
    slots.claim(1, high, 6).shouldBeFalse()
    withClue("the evicted low still read as filled") { slots.claim(1, high, 5).shouldBeTrue() }
  }

  @Test
  fun `sixteen bit sample tables give exactly what evaluating each curve gives`() {
    val curves = listOf(IccCurve.Pq, IccCurve.HlgScene, IccCurve.Srgb, IccCurve.Gamma(1 / 0.45))
    val parsed =
      listOf(
        TestIccProfiles.displayP3,
        TestIccProfiles.gamma22,
        TestIccProfiles.sparseSrgbTable,
        TestIccProfiles.grey22,
        TestIccProfiles.mab,
      )
    val profiles =
      curves.map { describedProfile(P3_PRIMARIES + doubleArrayOf(0.3127, 0.3290), it).shouldNotBeNull() } +
        parsed.map { parseIccProfile(it).shouldNotBeNull() }
    val samples = (0 until 65535 step 97) + 65535
    profiles.forEachIndexed { index, profile ->
      val tabled = profile.sampleReader(tabulate = true)
      val evaluated = profile.sampleReader(tabulate = false)
      for (sample in samples) {
        val red = sample
        val green = (sample * 7) % 65536
        val blue = (sample * 13) % 65536
        val wanted = profile.toPcs(red / 65535.0, green / 65535.0, blue / 65535.0)
        val fromTable = DoubleArray(3).also { tabled.read(red, green, blue, it) }
        val fromCurve = DoubleArray(3).also { evaluated.read(red, green, blue, it) }
        withClue("profile $index sample $sample from the table") { fromTable shouldBe wanted }
        withClue("profile $index sample $sample from the curve") { fromCurve shouldBe wanted }
      }
    }
  }

  @Test
  fun `the per code tables and the slot cache give what each color gives alone`() {
    // The dense grid holds more colors than the cache has slots, so colors evict each other on the way.
    val dense = gridColors(steps = 26)
    withClue("the dense grid fits in the cache") { (dense.size > ColorSlots(dense.size).size).shouldBeTrue() }
    val pictures = listOf(gridColors(), dense).map { it + it.reversedArray() }
    val profiles =
      listOf(
        TestIccProfiles.gamma22,
        TestIccProfiles.nudgedSrgb,
        TestIccProfiles.linearSrgb,
        TestIccProfiles.displayP3,
        TestIccProfiles.bradfordSrgb,
        TestIccProfiles.sparseSrgbTable,
        TestIccProfiles.mab,
        TestIccProfiles.grey22,
      )

    for (colors in pictures) {
      profiles.forEachIndexed { index, profile -> assertMatchesAlone(profile, colors, "profile $index") }
    }
  }

  private fun assertMatchesAlone(
    profile: ByteArray,
    colors: IntArray,
    what: String,
  ) {
    val color = parseIccProfile(profile).shouldNotBeNull()
    val nits = FloatArray(colors.size * 4)
    val codes = IntArray(colors.size)
    colors.forEachIndexed { texel, argb ->
      val wide = alone(color, argb, PCS_TO_BT2020)
      for (channel in 0 until 3) nits[texel * 4 + channel] = (wide[channel] * HDR_REFERENCE_WHITE_NITS).toFloat()
      nits[texel * 4 + 3] = 1f
      val narrow = alone(color, argb, PCS_TO_BT709)
      codes[texel] =
        narrow.fold(0xFF) { packed, light -> (packed shl 8) or (linearToSrgb(light.toFloat()) * 255.0).roundToInt() }
    }

    withClue("$what on HDR") { pictureLight(Icc(profile), colors) shouldBe nits }
    withClue("$what on SDR") { pictureSrgbCodes(Icc(profile), colors) shouldBe codes }
  }

  // Each color through toPcs and the connection space matrix on its own, with no table or cache in between.
  private fun alone(
    profile: IccProfile,
    color: Int,
    pcsToRgb: DoubleArray,
  ): DoubleArray {
    val pcs = profile.toPcs((color shr 16 and 0xFF) / 255.0, (color shr 8 and 0xFF) / 255.0, (color and 0xFF) / 255.0)
    return DoubleArray(3) { row ->
      (pcsToRgb[row * 3] * pcs[0] + pcsToRgb[row * 3 + 1] * pcs[1] + pcsToRgb[row * 3 + 2] * pcs[2]).coerceIn(0.0, 1.0)
    }
  }

  private fun gridColors(steps: Int = 17): IntArray {
    val levels = (0 until steps).map { (it * 255.0 / (steps - 1)).roundToInt() }
    return levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> argb(r, g, b) } } }.toIntArray()
  }

  private fun pcsToBt2020(): DoubleArray =
    TestColorMath.multiply(
      TestColorMath.invert(TestColorMath.rgbToXyz(BT2020_PRIMARIES)),
      TestColorMath.bradford(TestColorMath.D50, TestColorMath.D65),
    )

  private fun DoubleArray.toCodes(scale: Int): IntArray = IntArray(size) { this[it].roundToInt() * scale }

  private fun p3InBt2020(column: Int): DoubleArray {
    val p3ToBt2020 =
      TestColorMath.multiply(
        TestColorMath.invert(TestColorMath.rgbToXyz(BT2020_PRIMARIES)),
        TestColorMath.rgbToXyz(P3_PRIMARIES),
      )
    return DoubleArray(3) { p3ToBt2020[it * 3 + column] }
  }

  private fun codes(
    nits: FloatArray,
    transfer: HdrTransfer,
  ): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(nits))

  private infix fun Float.shouldBeNear(expected: Float) = abs(this - expected) shouldBeUnder NITS_TOLERANCE

  private infix fun Float.shouldBeUnder(limit: Float) =
    withClue("off by $this, over $limit") {
      (this <= limit).shouldBeTrue()
    }

  private fun argb(
    red: Int,
    green: Int,
    blue: Int,
  ): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

  private companion object {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MID_GREY = 0xFF808080.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val HALF_ALPHA_TEAL = 0x80208A90.toInt()
    const val OFF_GREY = 0xFF406080.toInt()
    const val NITS_TOLERANCE = 0.05f
  }
}
