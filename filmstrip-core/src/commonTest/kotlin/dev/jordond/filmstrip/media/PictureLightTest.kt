package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.media.TestIccProfiles.BT2020_PRIMARIES
import dev.jordond.filmstrip.media.TestIccProfiles.BT709_PRIMARIES
import dev.jordond.filmstrip.media.TestIccProfiles.P3_PRIMARIES
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PictureLightTest {
  private val texels = intArrayOf(BLACK, MID_GREY, WHITE, RED, HALF_ALPHA_TEAL, OFF_GREY)

  @Test
  fun `an untagged picture lands on its stored codes and on authored light`() {
    assertContentEquals(texels, pictureSrgbCodes(null, texels))
    assertContentEquals(authoredHdrTexels(texels), pictureLight(null, texels))
  }

  @Test
  fun `an sRGB tagged picture lands exactly where an untagged one does`() {
    listOf(TestIccProfiles.srgb, TestIccProfiles.srgbTable).forEach { profile ->
      assertTrue(assertNotNull(parseIccProfile(profile)).isSrgb, "an sRGB profile did not read as sRGB")
      assertContentEquals(texels, pictureSrgbCodes(Icc(profile), texels))
      assertContentEquals(authoredHdrTexels(texels), pictureLight(Icc(profile), texels))
    }
  }

  @Test
  fun `a profile that is not an RGB or grey profile reads as untagged`() {
    val cmyk = TestIccProfiles.profile("CMYK", "Lab ", emptyList())
    val garbage = ByteArray(200) { (it * 7).toByte() }

    listOf(cmyk, garbage).forEach { profile ->
      assertContentEquals(texels, pictureSrgbCodes(Icc(profile), texels))
      assertContentEquals(authoredHdrTexels(texels), pictureLight(Icc(profile), texels))
    }
  }

  @Test
  fun `a gamma 2 point 2 curve and a nudged primary do not count as sRGB`() {
    listOf(TestIccProfiles.gamma22, TestIccProfiles.nudgedSrgb).forEach { profile ->
      assertFalse(assertNotNull(parseIccProfile(profile)).isSrgb, "a near sRGB profile read as sRGB")
    }

    // A grey keeps the profile's white chromaticity, so it lands on the stored gamma's light in every channel.
    val gamma = pictureLight(Icc(TestIccProfiles.gamma22), intArrayOf(MID_GREY))
    val wanted = (HDR_REFERENCE_WHITE_NITS * (128 / 255.0).pow(563 / 256.0)).toFloat()
    (0 until 3).forEach { gamma[it] shouldBeNear wanted }
    assertTrue(abs(gamma[1] - authoredHdrTexels(intArrayOf(MID_GREY))[1]) > NITS_TOLERANCE, "gamma 2.2 read as sRGB")
  }

  @Test
  fun `a linear light picture lands its mid code on that much light`() {
    val light = 128 / 255.0
    val code = (linearToSrgb(light.toFloat()) * 255f).roundToInt()

    assertEquals(argb(code, code, code), pictureSrgbCodes(Icc(TestIccProfiles.linearSrgb), intArrayOf(MID_GREY))[0])

    val nits = pictureLight(Icc(TestIccProfiles.linearSrgb), intArrayOf(MID_GREY))
    val expected = FloatArray(3) { (light * HDR_REFERENCE_WHITE_NITS).toFloat() }
    HdrTransfer.entries.forEach { transfer ->
      assertContentEquals(codes(expected, transfer), codes(nits.copyOfRange(0, 3), transfer), "$transfer")
    }
  }

  @Test
  fun `display P3 green keeps its full gamut on HDR`() {
    val expected = p3InBt2020(column = 1)
    assertTrue(expected.all { it > 0.0 }, "P3 green should sit inside BT.2020")

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(argb(0, 255, 0)))
    val wanted = FloatArray(3) { (expected[it] * HDR_REFERENCE_WHITE_NITS).toFloat() }
    (0 until 3).forEach { nits[it] shouldBeNear wanted[it] }
    HdrTransfer.entries.forEach { transfer ->
      assertContentEquals(codes(wanted, transfer), codes(nits.copyOfRange(0, 3), transfer), "$transfer")
    }
  }

  @Test
  fun `display P3 red reaches past BT 709 red on HDR and clips to it on SDR`() {
    val expected = p3InBt2020(column = 0)
    // P3's red sits a hair past BT.2020's red to green edge, so only blue leaves BT.2020 and clips.
    assertTrue(expected[2] < 0.0, "P3 red should sit just outside BT.2020 on blue")

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(RED))
    val wanted = FloatArray(3) { (expected[it].coerceAtLeast(0.0) * HDR_REFERENCE_WHITE_NITS).toFloat() }
    (0 until 3).forEach { nits[it] shouldBeNear wanted[it] }
    assertTrue(nits[0] > authoredHdrNits(RED)[0], "P3 red landed no further out than BT.709 red")
    HdrTransfer.entries.forEach { transfer ->
      assertContentEquals(codes(wanted, transfer), codes(nits.copyOfRange(0, 3), transfer), "$transfer")
    }

    assertEquals(RED, pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(RED))[0])
  }

  @Test
  fun `a display P3 mid grey lands on an sRGB mid grey`() {
    assertEquals(MID_GREY, pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(MID_GREY))[0])

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(MID_GREY))
    val authored = authoredHdrTexels(intArrayOf(MID_GREY))
    HdrTransfer.entries.forEach { transfer ->
      assertContentEquals(codes(authored.copyOfRange(0, 3), transfer), codes(nits.copyOfRange(0, 3), transfer))
    }
  }

  @Test
  fun `alpha carries through a tagged picture unchanged`() {
    val codes = pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(HALF_ALPHA_TEAL))
    assertEquals(HALF_ALPHA_TEAL ushr 24, codes[0] ushr 24)

    val nits = pictureLight(Icc(TestIccProfiles.displayP3), intArrayOf(HALF_ALPHA_TEAL))
    assertEquals(authoredHdrTexels(intArrayOf(HALF_ALPHA_TEAL))[3], nits[3])
  }

  @Test
  fun `a grey profile lifts its level through the grey curve onto white`() {
    val gamma = 563 / 256.0
    val nits = pictureLight(Icc(TestIccProfiles.grey22), intArrayOf(MID_GREY))

    (0 until 3).forEach { nits[it] shouldBeNear (HDR_REFERENCE_WHITE_NITS * (128 / 255.0).pow(gamma)).toFloat() }
  }

  @Test
  fun `a mid saturation display P3 colour lands on its BT 709 codes on SDR`() {
    val stored = intArrayOf(200, 120, 90)
    val p3ToBt709 =
      TestColourMath.multiply(
        TestColourMath.invert(TestColourMath.rgbToXyz(BT709_PRIMARIES)),
        TestColourMath.rgbToXyz(P3_PRIMARIES),
      )
    val light = TestColourMath.apply(p3ToBt709, DoubleArray(3) { srgbToLinear(stored[it] / 255f).toDouble() })
    assertTrue(light.all { it > 0.0 && it < 1.0 }, "the colour should sit inside BT.709")
    val wanted = light.map { (linearToSrgb(it.toFloat()) * 255).roundToInt() }

    val codes = pictureSrgbCodes(Icc(TestIccProfiles.displayP3), intArrayOf(argb(stored[0], stored[1], stored[2])))[0]

    assertEquals(argb(wanted[0], wanted[1], wanted[2]), codes)
    assertTrue(wanted != stored.toList(), "the colour is too grey to tell P3 from BT.709")
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
    assertTrue(a > 20 && b < -20, "the colour should sit well off the grey axis")
    val wanted = TestColourMath.labToXyz(lightness, a, b)

    listOf("lut8" to lut8, "lut16" to lut16, "mAB" to mab).forEach { (name, tag) ->
      val profile = TestIccProfiles.profile("RGB ", "Lab ", listOf("A2B0" to tag))
      val pcs = assertNotNull(parseIccProfile(profile)).toPcs(stored[0] / 255.0, stored[1] / 255.0, stored[2] / 255.0)
      (0 until 3).forEach { assertTrue(abs(pcs[it] - wanted[it]) <= 1e-6, "$name axis $it read ${pcs[it]}") }

      val nits = pictureLight(Icc(profile), intArrayOf(argb(stored[0], stored[1], stored[2])))
      val light = TestColourMath.apply(pcsToBt2020(), wanted).map { it.coerceIn(0.0, 1.0) }
      (0 until 3).forEach { nits[it] shouldBeNear (light[it] * HDR_REFERENCE_WHITE_NITS).toFloat() }
    }
  }

  @Test
  fun `a Bradford sRGB profile lands on authored light within its colorant rounding`() {
    assertFalse(assertNotNull(parseIccProfile(TestIccProfiles.bradfordSrgb)).isSrgb, "Bradford sRGB read as sRGB")
    // Each colorant is stored to the nearest s15Fixed16 step, half of 1/65536 off, and a channel sums three of them
    // through the BT.2020 matrix. The stored para curve sits up to one sixteen-bit step off the sRGB curve.
    val rowSum = (0 until 3).maxOf { row -> (0 until 3).sumOf { abs(PCS_TO_BT2020[row * 3 + it]) } }
    val colorantRounding = (HDR_REFERENCE_WHITE_NITS * (rowSum * 3 * 0.5 / 65536 + 1.0 / 65535)).toFloat()

    val grid = gridColours()
    val nits = pictureLight(Icc(TestIccProfiles.bradfordSrgb), grid)
    val authored = authoredHdrTexels(grid)

    val worst = nits.indices.filter { it % 4 != 3 }.maxOf { abs(nits[it] - authored[it]) }
    assertTrue(worst <= colorantRounding, "off by $worst nits, over $colorantRounding")
  }

  @Test
  fun `a picture gets its texel count in colour slots rounded up to a power of two and at most 16384`() {
    assertEquals(2, ColourSlots(1).size)
    assertEquals(4, ColourSlots(3).size)
    assertEquals(1024, ColourSlots(1000).size)
    assertEquals(1 shl 14, ColourSlots(1 shl 14).size)
    assertEquals(1 shl 14, ColourSlots(1 shl 20).size)
  }

  @Test
  fun `wide slots tell colours apart by their low key and never read the high key of all ones as filled`() {
    val slots = ColourSlots(4, wide = true)
    val allOnes = (65535 shl 16) or 65535
    assertTrue(slots.claim(0, allOnes, 65535), "an empty slot read as red and green 65535")
    assertFalse(slots.claim(0, allOnes, 65535), "a filled slot missed its own colour")
    assertTrue(slots.slotOf(allOnes, 65535) in 0 until slots.size)

    val high = (1200 shl 16) or 34000
    assertTrue(slots.claim(1, high, 5))
    assertTrue(slots.claim(1, high, 6), "a second low under the same high key read as the first")
    assertFalse(slots.claim(1, high, 6))
    assertTrue(slots.claim(1, high, 5), "the evicted low still read as filled")
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
      curves.map { assertNotNull(describedProfile(P3_PRIMARIES + doubleArrayOf(0.3127, 0.3290), it)) } +
        parsed.map { assertNotNull(parseIccProfile(it)) }
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
        assertContentEquals(wanted, fromTable, "profile $index sample $sample from the table")
        assertContentEquals(wanted, fromCurve, "profile $index sample $sample from the curve")
      }
    }
  }

  @Test
  fun `the per code tables and the slot cache give what each colour gives alone`() {
    // The dense grid holds more colours than the cache has slots, so colours evict each other on the way.
    val dense = gridColours(steps = 26)
    assertTrue(dense.size > ColourSlots(dense.size).size, "the dense grid fits in the cache")
    val pictures = listOf(gridColours(), dense).map { it + it.reversedArray() }
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

    for (colours in pictures) {
      profiles.forEachIndexed { index, profile -> assertMatchesAlone(profile, colours, "profile $index") }
    }
  }

  private fun assertMatchesAlone(
    profile: ByteArray,
    colours: IntArray,
    what: String,
  ) {
    val colour = assertNotNull(parseIccProfile(profile))
    val nits = FloatArray(colours.size * 4)
    val codes = IntArray(colours.size)
    colours.forEachIndexed { texel, color ->
      val wide = alone(colour, color, PCS_TO_BT2020)
      for (channel in 0 until 3) nits[texel * 4 + channel] = (wide[channel] * HDR_REFERENCE_WHITE_NITS).toFloat()
      nits[texel * 4 + 3] = 1f
      val narrow = alone(colour, color, PCS_TO_BT709)
      codes[texel] =
        narrow.fold(0xFF) { packed, light -> (packed shl 8) or (linearToSrgb(light.toFloat()) * 255.0).roundToInt() }
    }

    assertContentEquals(nits, pictureLight(Icc(profile), colours), "$what on HDR")
    assertContentEquals(codes, pictureSrgbCodes(Icc(profile), colours), "$what on SDR")
  }

  // Each colour through toPcs and the connection space matrix on its own, with no table or cache in between.
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

  private fun gridColours(steps: Int = 17): IntArray {
    val levels = (0 until steps).map { (it * 255.0 / (steps - 1)).roundToInt() }
    return levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> argb(r, g, b) } } }.toIntArray()
  }

  private fun pcsToBt2020(): DoubleArray =
    TestColourMath.multiply(
      TestColourMath.invert(TestColourMath.rgbToXyz(BT2020_PRIMARIES)),
      TestColourMath.bradford(TestColourMath.D50, TestColourMath.D65),
    )

  private fun DoubleArray.toCodes(scale: Int): IntArray = IntArray(size) { this[it].roundToInt() * scale }

  private fun p3InBt2020(column: Int): DoubleArray {
    val p3ToBt2020 =
      TestColourMath.multiply(
        TestColourMath.invert(TestColourMath.rgbToXyz(BT2020_PRIMARIES)),
        TestColourMath.rgbToXyz(P3_PRIMARIES),
      )
    return DoubleArray(3) { p3ToBt2020[it * 3 + column] }
  }

  private fun codes(
    nits: FloatArray,
    transfer: HdrTransfer,
  ): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(nits))

  private infix fun Float.shouldBeNear(expected: Float) = abs(this - expected) shouldBeUnder NITS_TOLERANCE

  private infix fun Float.shouldBeUnder(limit: Float) = assertTrue(this <= limit, "off by $this, over $limit")

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
