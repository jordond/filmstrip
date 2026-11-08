package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.color.TestIccProfiles.BT2020_PRIMARIES
import dev.jordond.filmstrip.color.TestIccProfiles.P3_PRIMARIES
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Lands PQ and HLG pictures described by CICP code points, checking each against core's own transfer functions at
 * signals in the middle of the range, and checks that sixteen-bit samples land where their eight-bit codes do.
 */
class PictureColorHdrTest {
  @Test
  fun `a pq picture keeps the absolute light of each channel's signal on HDR`() {
    val signals = quantized(0.4, 0.5, 0.58)
    val nits = pictureLight(PQ_BT2020, samples(signals))
    for (channel in 0 until 3) assertRelative(nitsFromPqSignal(signals[channel]), nits[channel], "channel $channel")
    val codes = pictureLight(PQ_BT2020, intArrayOf(color(102, 128, 148)))
    for ((channel, code) in listOf(102, 128, 148).withIndex()) {
      assertRelative(nitsFromPqSignal(code / 255.0), codes[channel], "code $code")
    }
  }

  @Test
  fun `a pq picture reads against reference white and encodes as sRGB on SDR`() {
    val signals = quantized(0.4, 0.5, 0.58)
    val codes = pictureSrgbCodes(PQ_BT709, samples(signals)).single()
    for (channel in 0 until 3) {
      val wanted = srgbCode(nitsFromPqSignal(signals[channel]) / HDR_REFERENCE_WHITE_NITS)
      withClue("channel $channel") { channelOf(codes, channel) shouldBe wanted }
    }
    withClue("the signals sit mid range on SDR") { (0 until 3).all { channelOf(codes, it) in 1..254 }.shouldBeTrue() }
  }

  @Test
  fun `pq light above reference white keeps its nits on HDR and clips on SDR`() {
    val signal = quantized(0.75)
    val bright = samples(doubleArrayOf(signal[0], signal[0], signal[0]))
    val wanted = nitsFromPqSignal(signal[0])
    withClue("0.75 sits well above reference white") { (wanted > 4 * HDR_REFERENCE_WHITE_NITS).shouldBeTrue() }
    val nits = pictureLight(PQ_BT2020, bright)
    for (channel in 0 until 3) assertRelative(wanted, nits[channel], "channel $channel")
    pictureSrgbCodes(PQ_BT709, bright).single() shouldBe 0xFFFFFFFF.toInt()
    val peak = pictureLight(PQ_BT2020, samples(doubleArrayOf(1.0, 0.0, 0.0)))
    assertRelative(PQ_PEAK_NITS.toDouble(), peak[0], "the full signal")
  }

  @Test
  fun `an hlg picture lands where hlgNitsFromSignal reads all three channels at once on HDR`() {
    for (signals in listOf(quantized(0.5, 0.5, 0.5), quantized(0.5, 0.42, 0.61))) {
      val wanted = hlgNitsFromSignal(signals)
      val nits = pictureLight(HLG_BT2020, samples(signals))
      for (channel in 0 until 3) assertRelative(wanted[channel], nits[channel], "${signals.toList()} $channel")
    }
    withClue("HLG mid grey passed white") {
      (hlgNitsFromSignal(quantized(0.5, 0.5, 0.5))[0] < HDR_REFERENCE_WHITE_NITS).shouldBeTrue()
    }
  }

  @Test
  fun `hlg light above reference white keeps its nits up to the nominal peak on HDR`() {
    for (signals in listOf(quantized(0.9, 0.9, 0.9), quantized(1.0, 1.0, 1.0), quantized(1.0, 0.9, 0.95))) {
      val wanted = hlgNitsFromSignal(signals)
      withClue("${signals.toList()} sits under twice white") {
        wanted.all { it > 2 * HDR_REFERENCE_WHITE_NITS }.shouldBeTrue()
      }
      val nits = pictureLight(HLG_BT2020, samples(signals))
      for (channel in 0 until 3) assertRelative(wanted[channel], nits[channel], "${signals.toList()} $channel")
    }
  }

  @Test
  fun `hlg light above reference white clips per channel on SDR`() {
    val bright = quantized(0.9, 0.9, 0.9)
    withClue("0.9 sits under reference white") {
      (hlgNitsFromSignal(bright)[0] > HDR_REFERENCE_WHITE_NITS).shouldBeTrue()
    }
    pictureSrgbCodes(HLG_BT709, samples(bright)).single() shouldBe 0xFFFFFFFF.toInt()

    val signals = quantized(0.95, 0.3, 0.3)
    val scene = DoubleArray(3) { sceneFromHlgSignal(signals[it]) }
    val luminance = 0.2126 * scene[0] + 0.7152 * scene[1] + 0.0722 * scene[2]
    val gain = luminance.pow(HLG_SYSTEM_GAMMA - 1) * HLG_NOMINAL_PEAK_NITS / HDR_REFERENCE_WHITE_NITS
    withClue("the red channel alone passes white") { (scene[0] * gain > 1 && scene[1] * gain < 1).shouldBeTrue() }
    val codes = pictureSrgbCodes(HLG_BT709, samples(signals)).single()
    withClue("red past white") { channelOf(codes, 0) shouldBe 255 }
    for (channel in 1 until 3) {
      val off = abs(srgbCode(scene[channel] * gain) - channelOf(codes, channel))
      withClue("channel $channel under white is $off codes off") { (off <= 1).shouldBeTrue() }
    }
  }

  @Test
  fun `display p3 pq and hlg pictures land in their own primaries on HDR`() {
    val signals = quantized(0.62, 0.45, 0.3)
    val p3ToBt2020 =
      TestColorMath.multiply(
        TestColorMath.invert(TestColorMath.rgbToXyz(BT2020_PRIMARIES)),
        TestColorMath.rgbToXyz(P3_PRIMARIES),
      )

    val pqWanted = TestColorMath.apply(p3ToBt2020, DoubleArray(3) { nitsFromPqSignal(signals[it]) })
    val pq = pictureLight(Cicp(12, 16, 0, true), samples(signals))
    for (channel in 0 until 3) assertRelative(pqWanted[channel], pq[channel], "pq channel $channel")

    val scene = TestColorMath.apply(p3ToBt2020, DoubleArray(3) { sceneFromHlgSignal(signals[it]) })
    val hlgWanted = hlgNitsFromScene(scene)
    val hlg = pictureLight(Cicp(12, 18, 0, true), samples(signals))
    for (channel in 0 until 3) assertRelative(hlgWanted[channel], hlg[channel], "hlg channel $channel")
    val asBt2020 = hlgNitsFromSignal(signals)
    withClue("P3 HLG landed where BT.2020 HLG does") {
      (0 until 3).any { abs(hlg[it] - asBt2020[it]) > 1 }.shouldBeTrue()
    }
  }

  @Test
  fun `an hlg picture reads against reference white on SDR`() {
    val half = quantized(0.5, 0.5, 0.5)
    val grey = pictureSrgbCodes(HLG_BT709, samples(half)).single()
    val wantedGrey = srgbCode(hlgNitsFromSignal(half)[0] / HDR_REFERENCE_WHITE_NITS)
    for (channel in 0 until 3) withClue("grey channel $channel") { channelOf(grey, channel) shouldBe wantedGrey }

    // The opto-optical transfer reads the color's luminance, which BT.709 primaries weigh with their own figures.
    val signals = quantized(0.5, 0.42, 0.61)
    val scene = DoubleArray(3) { sceneFromHlgSignal(signals[it]) }
    val luminance = 0.2126 * scene[0] + 0.7152 * scene[1] + 0.0722 * scene[2]
    val gain = luminance.pow(HLG_SYSTEM_GAMMA - 1) * HLG_NOMINAL_PEAK_NITS / HDR_REFERENCE_WHITE_NITS
    val codes = pictureSrgbCodes(HLG_BT709, samples(signals)).single()
    for (channel in 0 until 3) {
      val off = abs(srgbCode(scene[channel] * gain) - channelOf(codes, channel))
      withClue("channel $channel is $off codes off") { (off <= 1).shouldBeTrue() }
    }
  }

  @Test
  fun `a description whose light tops out at reference white still clips there on HDR`() {
    val grid = gridColors()
    val xyz = pictureLight(Cicp(10, 8, 0, true), grid)
    withClue("XYZ passed white") {
      xyz.indices
        .filter { it % 4 != 3 }
        .all { xyz[it] <= HDR_REFERENCE_WHITE_NITS }
        .shouldBeTrue()
    }
  }

  @Test
  fun `sixteen bit samples land exactly where their eight bit codes do`() {
    val grid = gridColors()
    val wide =
      ShortArray(grid.size * 4) { index ->
        val color = grid[index / 4]
        val shift = intArrayOf(16, 8, 0, 24)[index % 4]
        (((color ushr shift) and 0xFF) * 257).toShort()
      }
    val descriptions =
      listOf(
        null,
        Cicp(1, 13, 0, true),
        Cicp(12, 13, 0, true),
        Cicp(9, 1, 0, true),
        PQ_BT2020,
        HLG_BT2020,
        PngGamma(50000, null),
        Icc(TestIccProfiles.displayP3),
        Icc(TestIccProfiles.grey22),
      )
    for (color in descriptions) {
      withClue("$color on HDR") { pictureLight(color, wide) shouldBe pictureLight(color, grid) }
      withClue("$color on SDR") { pictureSrgbCodes(color, wide) shouldBe pictureSrgbCodes(color, grid) }
    }
  }

  @Test
  fun `a large sixteen bit picture lands every texel where it lands alone`() {
    // Few red and green pairs over many blues, so slots evict colors that share a high key but not a low one. The
    // picture holds more texels than the cache, so it reads its curves from tables where a lone texel evaluates them.
    val random = Random(20261009)
    val highs = listOf(65535 to 65535, 0 to 0, 40000 to 12345, random.nextInt(65536) to random.nextInt(65536))
    val lows = IntArray(1000) { random.nextInt(65536) }
    val count = 60_000
    val picked = IntArray(count) { random.nextInt(highs.size) }
    val blues = IntArray(count) { lows[random.nextInt(lows.size)] }
    val rgba =
      ShortArray(count * 4) { index ->
        val texel = index / 4
        val (red, green) = highs[picked[texel]]
        intArrayOf(red, green, blues[texel], blues[texel] xor 0x5A5A)[index % 4].toShort()
      }
    val slots = ColorSlots(count, wide = true)
    withClue("the picture fits in the cache") { (count > slots.size).shouldBeTrue() }
    val sharedSlots =
      highs.indices.flatMap { high -> lows.map { slots.slotOf(highKey(highs[high]), it) } }.groupBy { it }.count {
        it.value.size > 1
      }
    withClue("no two colors share a slot") { (sharedSlots > 0).shouldBeTrue() }

    for (color in listOf(PQ_BT2020, HLG_BT2020, Icc(TestIccProfiles.displayP3), null)) {
      val nits = pictureLight(color, rgba)
      val codes = pictureSrgbCodes(color, rgba)
      val alone = HashMap<Long, Pair<FloatArray, Int>>()
      for (texel in 0 until count) {
        val key = (picked[texel].toLong() shl 16) or blues[texel].toLong()
        val (wantedNits, wantedCode) =
          alone.getOrPut(key) {
            val one = rgba.copyOfRange(texel * 4, texel * 4 + 4)
            pictureLight(color, one) to pictureSrgbCodes(color, one).single()
          }
        withClue("$color texel $texel on HDR") { nits.copyOfRange(texel * 4, texel * 4 + 4) shouldBe wantedNits }
        withClue("$color texel $texel on SDR") { codes[texel] shouldBe wantedCode }
      }
    }
  }

  @Test
  fun `an untagged sixteen bit sample reads its level rounded to a float on every platform`() {
    // Kotlin/JS divides at double precision, so the wanted levels are stored in a FloatArray the way the JVM rounds.
    val levels = floatArrayOf(0x1234 / 65535f, 0x8000 / 65535f, 0xBEEF / 65535f)
    val wanted = authoredHdrNits(levels[0], levels[1], levels[2])
    val nits = pictureLight(null, shortArrayOf(0x1234, 0x8000.toShort(), 0xBEEF.toShort(), -1))
    nits.copyOf(3) shouldBe wanted
  }

  @Test
  fun `sixteen bit samples that are not whole texels are refused`() {
    assertFailsWith<IllegalArgumentException> { pictureLight(PQ_BT2020, ShortArray(5)) }
    assertFailsWith<IllegalArgumentException> { pictureSrgbCodes(null, ShortArray(6)) }
  }

  @Test
  fun `sixteen bit samples between eight bit codes are read at their own depth`() {
    val between = shortArrayOf(0x8000.toShort(), 0x8000.toShort(), 0x8000.toShort(), -1)
    val nits = pictureLight(PQ_BT2020, between)
    assertRelative(nitsFromPqSignal(0x8000 / 65535.0), nits[0], "a sample between codes")
    val truncated = pictureLight(PQ_BT2020, intArrayOf(color(0x80, 0x80, 0x80)))
    withClue("the sample was cut to eight bits") { (abs(nits[0] - truncated[0]) > 0.1f).shouldBeTrue() }
    val untagged = pictureSrgbCodes(null, intArrayOf(0x807F, 0x7F80, 0x0080, 0xFFFF).map(Int::toShort).toShortArray())
    untagged.single() shouldBe color(0x80, 0x7F, 0x00)
  }

  // Each signal moved onto the nearest sixteen-bit sample, so the expected light reads the sample the picture stores.
  private fun quantized(vararg signals: Double): DoubleArray =
    DoubleArray(signals.size) { (signals[it] * 65535).roundToInt() / 65535.0 }

  private fun highKey(pair: Pair<Int, Int>): Int = (pair.first shl 16) or pair.second

  private fun samples(signals: DoubleArray): ShortArray =
    ShortArray(4) { if (it < 3) (signals[it] * 65535).roundToInt().toShort() else -1 }

  private fun assertRelative(
    wanted: Double,
    nits: Float,
    what: String,
  ) {
    withClue("$what read $nits, wanted $wanted") { (abs(nits - wanted) <= maxOf(wanted * 1e-6, 1e-4)).shouldBeTrue() }
  }

  private fun srgbCode(light: Double): Int = (linearToSrgb(light.toFloat().coerceIn(0f, 1f)) * 255).roundToInt()

  private fun channelOf(
    argb: Int,
    channel: Int,
  ): Int = (argb ushr (16 - channel * 8)) and 0xFF

  private fun gridColors(steps: Int = 9): IntArray {
    val levels = (0 until steps).map { (it * 255.0 / (steps - 1)).roundToInt() }
    return levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> color(r, g, b) } } }.toIntArray()
  }

  private fun color(
    red: Int,
    green: Int,
    blue: Int,
  ): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

  private companion object {
    val PQ_BT2020 = Cicp(9, 16, 0, true)
    val PQ_BT709 = Cicp(1, 16, 0, true)
    val HLG_BT2020 = Cicp(9, 18, 0, true)
    val HLG_BT709 = Cicp(1, 18, 0, true)
  }
}
