package dev.jordond.filmstrip.internal

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Reads hand-built PNGs of every colour type and bit depth, through every filter, Adam7 and `tRNS`, against the samples
 * they store.
 */
class PngSamplesTest {
  private val rgbPalette = byteArrayOf(10, 20, 30, 200.toByte(), 100, 50, 0, 0, 0, -1, -1, -1)

  @Test
  fun `grey at every depth scales to sixteen bits`() {
    pixels(grey(1, 1, 0, 1)) shouldBe listOf(grey(65535), grey(0), grey(65535))
    pixels(grey(2, 0, 1, 2)) shouldBe listOf(grey(0), grey(21845), grey(43690))
    pixels(grey(4, 3, 8, 15)) shouldBe listOf(grey(13107), grey(34952), grey(65535))
    pixels(grey(8, 0, 128, 255)) shouldBe listOf(grey(0), grey(32896), grey(65535))
    pixels(grey(16, 0x1234, 0, 65535)) shouldBe listOf(grey(0x1234), grey(0), grey(65535))
  }

  @Test
  fun `a sixteen bit sample keeps its stored value`() {
    val decoded = pngSamples(encodePng(1, 1, 2, 16, intArrayOf(0x1234, 0x8001, 0xFFFE))).shouldNotBeNull()
    decoded.rgba.map { it.toInt() and 0xFFFF } shouldBe listOf(0x1234, 0x8001, 0xFFFE, 65535)
    decoded.sixteenBit shouldBe true
    pngSamples(encodePng(1, 1, 2, 8, intArrayOf(1, 2, 3))).shouldNotBeNull().sixteenBit shouldBe false
  }

  @Test
  fun `colour and alpha samples read in order`() {
    pixels(encodePng(2, 1, 2, 8, intArrayOf(200, 100, 50, 1, 2, 3))) shouldBe
      listOf(listOf(51400, 25700, 12850, 65535), listOf(257, 514, 771, 65535))
    pixels(encodePng(1, 1, 2, 16, intArrayOf(1000, 30000, 60000))) shouldBe listOf(listOf(1000, 30000, 60000, 65535))
    pixels(encodePng(2, 1, 4, 8, intArrayOf(128, 64, 7, 255))) shouldBe
      listOf(listOf(32896, 32896, 32896, 16448), listOf(1799, 1799, 1799, 65535))
    pixels(encodePng(1, 1, 4, 16, intArrayOf(4660, 22136))) shouldBe listOf(listOf(4660, 4660, 4660, 22136))
    pixels(encodePng(1, 1, 6, 8, intArrayOf(1, 2, 3, 4))) shouldBe listOf(listOf(257, 514, 771, 1028))
    pixels(encodePng(1, 1, 6, 16, intArrayOf(1, 2, 3, 40000))) shouldBe listOf(listOf(1, 2, 3, 40000))
  }

  @Test
  fun `a palette index takes its entry at every depth`() {
    val expected = listOf(listOf(51400, 25700, 12850, 65535), listOf(2570, 5140, 7710, 65535))
    for (depth in listOf(1, 2, 4, 8)) {
      pixels(encodePng(2, 1, 3, depth, intArrayOf(1, 0), palette = rgbPalette.copyOf(6))) shouldBe expected
    }
    pixels(encodePng(3, 1, 3, 2, intArrayOf(3, 2, 1), palette = rgbPalette)) shouldBe
      listOf(listOf(65535, 65535, 65535, 65535), listOf(0, 0, 0, 65535), expected[0])
  }

  @Test
  fun `tRNS gives palette entries alpha and leaves the rest opaque`() {
    val png = encodePng(3, 1, 3, 8, intArrayOf(0, 1, 3), palette = rgbPalette, transparency = byteArrayOf(0, 127))
    pixels(png).map { it[3] } shouldBe listOf(0, 32639, 65535)
  }

  @Test
  fun `tRNS makes a grey or RGB key colour transparent and keeps its samples`() {
    pixels(encodePng(3, 1, 0, 8, intArrayOf(5, 128, 6), transparency = byteArrayOf(0, -128))) shouldBe
      listOf(grey(1285), listOf(32896, 32896, 32896, 0), grey(1542))
    pixels(encodePng(3, 1, 0, 2, intArrayOf(1, 2, 3), transparency = byteArrayOf(0, 2))).map { it[3] } shouldBe
      listOf(65535, 0, 65535)
    pixels(encodePng(2, 1, 0, 16, intArrayOf(0x1234, 0x1235), transparency = byteArrayOf(0x12, 0x34))) shouldBe
      listOf(listOf(4660, 4660, 4660, 0), grey(4661))
    val key = byteArrayOf(0, 1, 0, 2, 0, 3)
    pixels(encodePng(2, 1, 2, 8, intArrayOf(1, 2, 3, 1, 2, 4), transparency = key)) shouldBe
      listOf(listOf(257, 514, 771, 0), listOf(257, 514, 1028, 65535))
    pixels(encodePng(2, 1, 2, 16, intArrayOf(1, 2, 3, 1, 2, 4), transparency = key)).map { it[3] } shouldBe
      listOf(0, 65535)
  }

  @Test
  fun `a tRNS that fails its CRC or does not fit the colour type is left out`() {
    val keyed = encodePng(1, 1, 0, 8, intArrayOf(9), transparency = byteArrayOf(0, 9))
    pixels(keyed).single()[3] shouldBe 0
    // The signature and IHDR take 33 bytes, so the tRNS CRC starts 10 bytes after that.
    val badCrc = keyed.copyOf().also { it[43] = (it[43].toInt() xor 1).toByte() }
    pixels(badCrc).single()[3] shouldBe 65535
    pixels(encodePng(1, 1, 0, 8, intArrayOf(9), transparency = byteArrayOf(0, 9, 0))).single()[3] shouldBe 65535
    pixels(encodePng(1, 1, 6, 8, intArrayOf(1, 2, 3, 4), transparency = byteArrayOf(0, 1))).single()[3] shouldBe 1028
    val tooLong = byteArrayOf(1, 2, 3, 4, 5)
    pixels(encodePng(1, 1, 3, 1, intArrayOf(0), palette = rgbPalette.copyOf(6), transparency = tooLong))
      .single()[3] shouldBe 65535
  }

  @Test
  fun `only the first tRNS ahead of the image data counts`() {
    val data = storedZlibBlocks(byteArrayOf(0, 9, 5))
    val first = listOf(transparencyChunk(0, 9))
    pixels(pngFile(2, 1, 0, 8, data, transparency = byteArrayOf(0, 5), extraChunks = first)).map { it[3] } shouldBe
      listOf(0, 65535)
    val afterImageData =
      bytes {
        raw(PNG_SIGNATURE)
        pngChunk("IHDR", pngHeader(2, 1, 8, 0, 0))
        pngChunk("IDAT", data)
        pngChunk("tRNS", byteArrayOf(0, 9))
        pngChunk("IEND", ByteArray(0))
      }
    pixels(afterImageData).map { it[3] } shouldBe listOf(65535, 65535)
  }

  @Test
  fun `a tRNS ahead of the palette is left out`() {
    val data = storedZlibBlocks(byteArrayOf(0, 0))
    pixels(pngFile(1, 1, 3, 8, data, palette = rgbPalette, transparency = byteArrayOf(0))).single()[3] shouldBe 0
    val early = listOf(transparencyChunk(0))
    pixels(pngFile(1, 1, 3, 8, data, palette = rgbPalette, extraChunks = early)).single()[3] shouldBe 65535
  }

  @Test
  fun `palette entries past what the depth reaches are dropped`() {
    val indices = intArrayOf(1, 0)
    pixels(encodePng(2, 1, 3, 1, indices, palette = rgbPalette)) shouldBe
      listOf(listOf(51400, 25700, 12850, 65535), listOf(2570, 5140, 7710, 65535))
    val inReach = byteArrayOf(0, 127)
    pixels(encodePng(2, 1, 3, 1, indices, palette = rgbPalette, transparency = inReach)).map { it[3] } shouldBe
      listOf(32639, 0)
    val pastReach = byteArrayOf(0, 127, 9)
    pixels(encodePng(2, 1, 3, 1, indices, palette = rgbPalette, transparency = pastReach)).map { it[3] } shouldBe
      listOf(65535, 65535)
  }

  @Test
  fun `a grey key at one and four bits makes its value transparent`() {
    pixels(encodePng(2, 1, 0, 1, intArrayOf(0, 1), transparency = byteArrayOf(0, 1))).map { it[3] } shouldBe
      listOf(65535, 0)
    pixels(encodePng(3, 1, 0, 4, intArrayOf(5, 10, 15), transparency = byteArrayOf(0, 10))).map { it[3] } shouldBe
      listOf(65535, 0, 65535)
  }

  @Test
  fun `a key wider than the bit depth is masked to it`() {
    pixels(encodePng(2, 1, 0, 1, intArrayOf(0, 1), transparency = byteArrayOf(0, 2))).map { it[3] } shouldBe
      listOf(0, 65535)
    pixels(encodePng(3, 1, 0, 4, intArrayOf(5, 10, 15), transparency = byteArrayOf(0, 0x15))).map { it[3] } shouldBe
      listOf(0, 65535, 65535)
    pixels(encodePng(2, 1, 0, 8, intArrayOf(9, 10), transparency = byteArrayOf(1, 9))).map { it[3] } shouldBe
      listOf(0, 65535)
    val key = byteArrayOf(1, 1, 2, 2, 3, 3)
    pixels(encodePng(2, 1, 2, 8, intArrayOf(1, 2, 3, 1, 2, 4), transparency = key)).map { it[3] } shouldBe
      listOf(0, 65535)
  }

  @Test
  fun `every filter reads back the samples it was written from`() {
    val random = Random(7)
    for ((colourType, depth) in PNG_FORMATS) {
      val samples = randomSamples(random, 7, 5, colourType, depth)
      val palette = if (colourType == 3) ByteArray(768) { random.nextInt(256).toByte() } else null
      val plain = pixels(encodePng(7, 5, colourType, depth, samples, palette = palette))
      for (strategy in FILTER_STRATEGIES) {
        pixels(encodePng(7, 5, colourType, depth, samples, filter = strategy, palette = palette)) shouldBe plain
      }
    }
  }

  @Test
  fun `Paeth breaks ties toward the left then above`() {
    // 100 is above left. Left 80 and above 110 tie left with above left, left 110 and above 80 tie above with it.
    paethCorner(left = 80, above = 110) shouldBe 80
    paethCorner(left = 110, above = 80) shouldBe 80
    paethCorner(left = 100, above = 100) shouldBe 100
  }

  @Test
  fun `Adam7 reads the same samples as the plain layout`() {
    val random = Random(11)
    val sizes = listOf(1 to 1, 3 to 3, 2 to 9, 9 to 2, 13 to 11)
    for ((colourType, depth) in PNG_FORMATS) {
      val palette = if (colourType == 3) ByteArray(768) { random.nextInt(256).toByte() } else null
      for ((width, height) in sizes) {
        val samples = randomSamples(random, width, height, colourType, depth)
        val plain = pixels(encodePng(width, height, colourType, depth, samples, palette = palette))
        for (strategy in FILTER_STRATEGIES) {
          val interlaced =
            encodePng(width, height, colourType, depth, samples, true, strategy, palette, imageDataChunk = 7)
          pixels(interlaced) shouldBe plain
        }
      }
    }
  }

  @Test
  fun `a one pixel Adam7 picture stores only its first pass`() {
    val png = encodePng(1, 1, 0, 8, intArrayOf(77), interlaced = true)
    pixels(png) shouldBe listOf(grey(19789))
    pngImageData(1, 1, 0, 8, intArrayOf(77), interlaced = true) { 0 }.size shouldBe 2
  }

  // A 2x2 grey picture whose bottom right pixel is predicted by Paeth from left, above and above left, with no residue.
  private fun paethCorner(
    left: Int,
    above: Int,
  ): Int {
    val aboveLeft = 100
    val data = byteArrayOf(0, aboveLeft.toByte(), above.toByte(), 4, (left - aboveLeft).toByte(), 0)
    return pixels(pngFile(2, 2, 0, 8, storedZlibBlocks(data)))[3][0] / 257
  }

  private fun grey(
    depth: Int,
    vararg values: Int,
  ): ByteArray = encodePng(values.size, 1, 0, depth, values)

  private fun grey(value: Int): List<Int> = listOf(value, value, value, 65535)

  private fun transparencyChunk(vararg data: Byte): ByteArray = bytes { pngChunk("tRNS", data) }
}

/**
 * Every filter type on every row, then all five in turn, then one picked at random per row.
 */
internal val FILTER_STRATEGIES: List<(Int) -> Int> =
  List(5) { type -> { _: Int -> type } } + listOf({ row: Int -> row % 5 }, { row: Int -> Random(row).nextInt(5) })

/**
 * The decoded pixels of [png] as red, green, blue and alpha samples.
 */
internal fun pixels(png: ByteArray): List<List<Int>> {
  val decoded = pngSamples(png).shouldNotBeNull()
  return decoded.rgba.map { it.toInt() and 0xFFFF }.chunked(4)
}

internal fun randomSamples(
  random: Random,
  width: Int,
  height: Int,
  colourType: Int,
  depth: Int,
): IntArray {
  val bound = if (colourType == 3) minOf(1 shl depth, 256) else 1 shl depth
  return IntArray(width * height * PNG_SAMPLES_PER_PIXEL.getValue(colourType)) { random.nextInt(bound) }
}
