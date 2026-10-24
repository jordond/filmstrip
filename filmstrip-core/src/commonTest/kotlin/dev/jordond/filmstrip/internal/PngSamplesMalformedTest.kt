package dev.jordond.filmstrip.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Cuts, corrupts and bends the rules of PNGs, so a read that throws, or that decodes something a PNG does not allow, is
 * caught.
 */
class PngSamplesMalformedTest {
  private val palette = byteArrayOf(10, 20, 30, 40, 50, 60)

  private val pictures =
    listOf(
      encodePng(5, 3, 0, 2, IntArray(15) { it % 4 }, filter = { it % 5 }),
      encodePng(4, 4, 2, 16, IntArray(48) { it * 1001 }, interlaced = true, filter = { 4 }, imageDataChunk = 9),
      encodePng(3, 2, 3, 1, intArrayOf(0, 1, 1, 0, 1, 0), palette = palette, transparency = byteArrayOf(9)),
      encodePng(3, 3, 6, 8, IntArray(36) { it * 7 }, interlaced = true, filter = { 3 }),
      encodePng(2, 2, 0, 8, intArrayOf(1, 2, 3, 4), transparency = byteArrayOf(0, 2)),
    )

  @Test
  fun `every picture decodes whole`() {
    pictures.forEach { pngSamples(it).shouldNotBeNull() }
  }

  @Test
  fun `a cut picture never decodes and never throws`() {
    for (picture in pictures) {
      for (length in picture.indices) pngSamples(picture.copyOf(length)).shouldBeNull()
    }
  }

  @Test
  fun `a corrupted picture never throws`() {
    val random = Random(41)
    for (picture in pictures) {
      repeat(400) {
        val corrupted = picture.copyOf()
        repeat(1 + it % 4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
        pngSamples(corrupted)
        pngSamples(withFixedCrcs(corrupted))
      }
    }
  }

  @Test
  fun `a picture whose rows and samples outgrow the byte cap is refused before it is decoded`() {
    // Four rows of a filter byte and four samples inflate to 20 bytes, and 16 pixels decode to 128.
    val grey = encodePng(4, 4, 0, 8, IntArray(16))
    pngSamples(grey, maxBytes = 148).shouldNotBeNull()
    pngSamples(grey, maxBytes = 147).shouldBeNull()
    // Four rows of a filter byte and 32 sample bytes inflate to 132 bytes.
    val deep = encodePng(4, 4, 6, 16, IntArray(64))
    pngSamples(deep, maxBytes = 260).shouldNotBeNull()
    pngSamples(deep, maxBytes = 259).shouldBeNull()
    // The five Adam7 passes a 4x4 picture fills store 23 bytes of rows.
    val interlaced = encodePng(4, 4, 0, 8, IntArray(16), interlaced = true)
    pngSamples(interlaced, maxBytes = 151).shouldNotBeNull()
    pngSamples(interlaced, maxBytes = 150).shouldBeNull()
    val data = storedZlibBlocks(ByteArray(16))
    pngSamples(pngFile(4096, 4097, 6, 16, data)).shouldBeNull()
    pngSamples(pngFile(Int.MAX_VALUE, Int.MAX_VALUE, 6, 16, data)).shouldBeNull()
  }

  @Test
  fun `the byte cap holds a 4096 square 16-bit RGBA picture in either layout and nothing larger`() {
    MAX_PNG_OVERLAY_SIDE shouldBe 4096L
    // Adam7 stores 7680 filter bytes for 4096 lines of 32768 sample bytes, then 8 bytes a pixel of samples.
    MAX_PNG_DECODE_BYTES shouldBe 7680L + 4096L * 32768 + 4096L * 4096 * 8
    header(4096, 4096, 6, 16, interlaced = true).shouldNotBeNull().decodeBytes shouldBe MAX_PNG_DECODE_BYTES
    // The plain picture stores 4096 rows, so it fits with 3584 bytes to spare.
    header(4096, 4096, 6, 16).shouldNotBeNull().decodeBytes shouldBe MAX_PNG_DECODE_BYTES - 3584
    for (interlaced in listOf(false, true)) {
      header(4096, 4097, 6, 16, interlaced).shouldBeNull()
      header(4097, 4096, 6, 16, interlaced).shouldBeNull()
    }
    // An 8-bit RGBA pixel holds 4 bytes of rows and 8 of samples, so 5461 rows of 4096 fit the same budget.
    header(4096, 5461, 6, 8).shouldNotBeNull()
    header(4096, 5462, 6, 8).shouldBeNull()
  }

  @Test
  fun `a huge interlaced picture is refused before its passes are sized`() {
    val data = storedZlibBlocks(ByteArray(16))
    pngSamples(pngFile(Int.MAX_VALUE, 1, 0, 8, data, interlaced = true)).shouldBeNull()
    pngSamples(pngFile(1, Int.MAX_VALUE, 0, 1, data, interlaced = true)).shouldBeNull()
    pngSamples(pngFile(Int.MAX_VALUE, Int.MAX_VALUE, 6, 16, data, interlaced = true)).shouldBeNull()
  }

  @Test
  fun `a failed CRC on a critical chunk refuses the picture`() {
    val picture = encodePng(1, 1, 3, 8, intArrayOf(1), palette = palette)
    val ihdrCrc = 8 + 8 + 13
    val plteCrc = ihdrCrc + 4 + 8 + palette.size
    val idatCrc = picture.size - 12 - 4
    for (at in listOf(ihdrCrc, plteCrc, idatCrc)) {
      pngSamples(picture.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }).shouldBeNull()
    }
  }

  @Test
  fun `image data split over several IDAT chunks reads only when they run together`() {
    val compressed = storedZlibBlocks(byteArrayOf(0, 1, 2, 3, 0, 4, 5, 6))
    pixels(pngFile(3, 2, 0, 8, compressed, imageDataChunk = 3)).map { it[0] / 257 } shouldBe listOf(1, 2, 3, 4, 5, 6)
    val split =
      bytes {
        raw(pngFile(3, 2, 0, 8, compressed.copyOf(5)).copyOfRange(0, 33 + 12 + 5))
        pngChunk("tEXt", asciiBytes("a\u0000b"))
        pngChunk("IDAT", compressed.copyOfRange(5, compressed.size))
        pngChunk("IEND", ByteArray(0))
      }
    pngSamples(split).shouldBeNull()
  }

  @Test
  fun `an unknown critical chunk refuses the picture and an unknown ancillary one is skipped`() {
    val data = storedZlibBlocks(byteArrayOf(0, 9))
    pngSamples(pngFile(1, 1, 0, 8, data, extraChunks = listOf(chunk("abCD")))).shouldNotBeNull()
    pngSamples(pngFile(1, 1, 0, 8, data, extraChunks = listOf(chunk("AbCD")))).shouldBeNull()
  }

  @Test
  fun `image data of the wrong size or with an undefined filter is refused`() {
    pngSamples(pngFile(1, 1, 0, 8, storedZlibBlocks(byteArrayOf(0, 9)))).shouldNotBeNull()
    pngSamples(pngFile(1, 1, 0, 8, storedZlibBlocks(byteArrayOf(0)))).shouldBeNull()
    pngSamples(pngFile(1, 1, 0, 8, storedZlibBlocks(byteArrayOf(5, 9)))).shouldBeNull()
    pngSamples(pngFile(1, 1, 0, 8, byteArrayOf(0x78, 0x01))).shouldBeNull()
    // Four rows of a filter byte and 32 sample bytes inflate to 132 bytes. A byte short is refused and a byte over is
    // dropped.
    for (size in listOf(131, 132, 133)) {
      val picture = pngFile(4, 4, 6, 16, storedZlibBlocks(ByteArray(size)))
      if (size == 131) pngSamples(picture).shouldBeNull() else pngSamples(picture).shouldNotBeNull()
    }
  }

  @Test
  fun `a palette that is missing or misplaced or too short for an index is refused`() {
    pngSamples(encodePng(1, 1, 3, 8, intArrayOf(1))).shouldBeNull()
    pngSamples(encodePng(1, 1, 3, 8, intArrayOf(2), palette = palette)).shouldBeNull()
    pngSamples(encodePng(1, 1, 3, 8, intArrayOf(1), palette = palette.copyOf(5))).shouldBeNull()
    pngSamples(encodePng(1, 1, 3, 8, intArrayOf(1), palette = ByteArray(771))).shouldBeNull()
    val plte = bytes { pngChunk("PLTE", palette) }
    val data = storedZlibBlocks(byteArrayOf(0, 1))
    pngSamples(pngFile(1, 1, 3, 8, data, palette = palette, extraChunks = listOf(plte))).shouldBeNull()
  }

  @Test
  fun `a PLTE in an RGB picture is skipped however broken`() {
    val rgb = storedZlibBlocks(byteArrayOf(0, 1, 2, 3))
    val expected = listOf(listOf(257, 514, 771, 65535))
    pixels(pngFile(1, 1, 2, 8, rgb, palette = palette)) shouldBe expected
    pixels(pngFile(1, 1, 2, 8, rgb, palette = palette.copyOf(5))) shouldBe expected
    pixels(pngFile(1, 1, 2, 8, rgb, palette = ByteArray(0))) shouldBe expected
    pixels(pngFile(1, 1, 2, 8, rgb, palette = ByteArray(771))) shouldBe expected
    val plte = bytes { pngChunk("PLTE", palette) }
    pixels(pngFile(1, 1, 2, 8, rgb, palette = palette, extraChunks = listOf(plte))) shouldBe expected
    // The signature and IHDR take 33 bytes, so the PLTE CRC starts 14 bytes after that.
    val badCrc = pngFile(1, 1, 2, 8, rgb, palette = palette).also { it[47] = (it[47].toInt() xor 1).toByte() }
    pixels(badCrc) shouldBe expected
    val afterImageData =
      bytes {
        raw(PNG_SIGNATURE)
        pngChunk("IHDR", pngHeader(1, 1, 8, 2, 0))
        pngChunk("IDAT", rgb)
        pngChunk("PLTE", palette)
        pngChunk("IEND", ByteArray(0))
      }
    pixels(afterImageData) shouldBe expected
    pixels(pngFile(1, 1, 6, 8, storedZlibBlocks(byteArrayOf(0, 1, 2, 3, 4)), palette = palette.copyOf(5))) shouldBe
      listOf(listOf(257, 514, 771, 1028))
  }

  // libpng warns about this and reads the picture.
  @Test
  fun `decompressed data past the last row is dropped`() {
    pixels(pngFile(1, 1, 0, 8, storedZlibBlocks(byteArrayOf(0, 9, 9)))) shouldBe listOf(listOf(2313, 2313, 2313, 65535))
    val oneRowTooMany = storedZlibBlocks(byteArrayOf(0, 1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9))
    pixels(pngFile(3, 2, 0, 8, oneRowTooMany)).map { it[0] / 257 } shouldBe listOf(1, 2, 3, 4, 5, 6)
    val cut = oneRowTooMany.copyOf(oneRowTooMany.size - 6)
    pngSamples(pngFile(3, 2, 0, 8, cut)).shouldBeNull()
  }

  @Test
  fun `a palette in a grey picture is skipped unless it is a second one ahead of the image data`() {
    pixels(encodePng(1, 1, 0, 8, intArrayOf(1), palette = palette)) shouldBe listOf(listOf(257, 257, 257, 65535))
    pixels(encodePng(1, 1, 4, 8, intArrayOf(1, 2), palette = palette)) shouldBe listOf(listOf(257, 257, 257, 514))
    val data = storedZlibBlocks(byteArrayOf(0, 1))
    val plte = bytes { pngChunk("PLTE", palette) }
    pngSamples(pngFile(1, 1, 0, 8, data, extraChunks = listOf(plte, plte))).shouldBeNull()
    val badCrc = plte.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    pngSamples(pngFile(1, 1, 0, 8, data, extraChunks = listOf(badCrc))).shouldBeNull()
    val afterImage =
      bytes {
        raw(pngFile(1, 1, 0, 8, data).let { it.copyOf(it.size - 12) })
        raw(plte)
        pngChunk("IEND", ByteArray(0))
      }
    pixels(afterImage) shouldBe listOf(listOf(257, 257, 257, 65535))
  }

  // libpng's sequential reader refuses a bad checksum zlib reaches in the read that wrote the last row, which for a
  // picture this small is the one read unless the checksum sits in a chunk of its own.
  @Test
  fun `a bad Adler-32 checksum in the read that ends the image is refused`() {
    val compressed = storedZlibBlocks(byteArrayOf(0, 1, 2, 3, 0, 4, 5, 6))
    val bad = compressed.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
    pngSamples(pngFile(3, 2, 0, 8, compressed)).shouldNotBeNull()
    pngSamples(pngFile(3, 2, 0, 8, bad)).shouldBeNull()
    val split = pngFile(3, 2, 0, 8, bad, imageDataChunk = bad.size - 4)
    pixels(split).map { it[0] / 257 } shouldBe listOf(1, 2, 3, 4, 5, 6)
  }

  @Test
  fun `a header PNG does not allow is refused`() {
    // Each picture's image data is the size its header asks for, so only the header refuses it.
    for ((colourType, depth) in listOf(0 to 3, 2 to 4, 3 to 16, 4 to 1, 6 to 2, 1 to 8, 5 to 8, 7 to 8)) {
      val bits = (PNG_SAMPLES_PER_PIXEL[colourType] ?: 1) * depth
      pngSamples(pngFile(1, 1, colourType, depth, storedZlibBlocks(ByteArray(1 + (bits + 7) / 8)))).shouldBeNull()
    }
    val data = storedZlibBlocks(ByteArray(2))
    pngSamples(pngFile(0, 1, 0, 8, data)).shouldBeNull()
    val header = pngHeader(1, 1, 8, 0, 0)
    pngSamples(withHeader(header, data)).shouldNotBeNull()
    for (field in 10..12) {
      pngSamples(withHeader(header.copyOf().also { it[field] = 2 }, data)).shouldBeNull()
    }
  }

  @Test
  fun `bytes that are not a PNG are refused`() {
    pngSamples(ByteArray(0)).shouldBeNull()
    pngSamples(asciiBytes("GIF89a")).shouldBeNull()
    pngSamples(png(colourType = 2).copyOf(8) + bytes { pngChunk("IDAT", ByteArray(8)) }).shouldBeNull()
  }

  private fun chunk(type: String): ByteArray = bytes { pngChunk(type, byteArrayOf(1, 2, 3)) }

  // Reads only the header of a picture this size against the real cap, so nothing is inflated or allocated for it.
  private fun header(
    width: Int,
    height: Int,
    colourType: Int,
    depth: Int,
    interlaced: Boolean = false,
  ): PngHeader? {
    val file = pngFile(width, height, colourType, depth, storedZlibBlocks(ByteArray(1)), interlaced)
    return ByteView(file, file.size).readPngHeader(PNG_SIGNATURE.size + 8, MAX_PNG_DECODE_BYTES)
  }

  private fun withHeader(
    header: ByteArray,
    compressed: ByteArray,
  ): ByteArray =
    bytes {
      raw(PNG_SIGNATURE)
      pngChunk("IHDR", header)
      pngChunk("IDAT", compressed)
      pngChunk("IEND", ByteArray(0))
    }
}
