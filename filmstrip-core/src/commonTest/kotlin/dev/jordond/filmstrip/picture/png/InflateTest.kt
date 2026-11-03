package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.picture.bytes.BitWriter
import dev.jordond.filmstrip.picture.fixtures.MAX_PROFILE
import dev.jordond.filmstrip.picture.fixtures.adler32Of
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Inflates zlib streams written by zlib itself and streams built bit by bit around a known output.
 *
 * The hex streams are what zlib writes at level 9 for [lettersProfile] and [periodicProfile]. The built streams are
 * one fixed Huffman block of literals followed by a run of zeros copied 258 bytes at a time, so a few kilobytes of
 * stream can inflate past any limit.
 */
class InflateTest {
  @Test
  fun `a dynamic huffman stream inflates`() {
    inflate(hex(DYNAMIC_ZLIB)) shouldBe lettersProfile()
  }

  @Test
  fun `a fixed huffman stream with back references inflates`() {
    inflate(hex(FIXED_ZLIB)) shouldBe periodicProfile()
  }

  @Test
  fun `a stream that inflates past the limit is refused`() {
    val bomb = zeroRunZlib(ByteArray(0), MAX_PROFILE + 1)
    bomb.size shouldBeLessThan MAX_PROFILE / 100
    inflate(bomb).shouldBeNull()
    inflate(zeroRunZlib(ByteArray(0), 1000), limit = 999).shouldBeNull()
    inflate(zeroRunZlib(ByteArray(0), 1000), limit = 1000) shouldBe ByteArray(1000)
    inflate(zeroRunZlib(ByteArray(0), 1000), limit = 5000) shouldBe ByteArray(1000)
  }

  @Test
  fun `a size prefixed stream inflates to exactly its declared size`() {
    val header = byteArrayOf(0, 0, 1, 44)
    inflate(zeroRunZlib(header, 296), sizePrefixed = true) shouldBe header + ByteArray(296)
    inflate(zeroRunZlib(header, 295), sizePrefixed = true).shouldBeNull()
    inflate(zeroRunZlib(header, 297), sizePrefixed = true).shouldBeNull()
    inflate(zeroRunZlib(header, MAX_PROFILE), sizePrefixed = true).shouldBeNull()
    inflate(zeroRunZlib(header, 296), limit = 299, sizePrefixed = true).shouldBeNull()
    inflate(zeroRunZlib(byteArrayOf(0, 0, 0, 3), 0), sizePrefixed = true).shouldBeNull()
    inflate(zeroRunZlib(byteArrayOf(0, 0), 0), sizePrefixed = true).shouldBeNull()
  }

  @Test
  fun `a stream whose checksum is wrong or that uses a preset dictionary is refused`() {
    val stream = hex(FIXED_ZLIB)
    stream[stream.lastIndex] = (stream.last() + 1).toByte()
    inflate(stream).shouldBeNull()
    inflate(byteArrayOf(0x78, 0xBB.toByte()) + hex(FIXED_ZLIB).copyOfRange(2, hex(FIXED_ZLIB).size)).shouldBeNull()
  }

  @Test
  fun `a stream read only up to its end bound is cut short`() {
    val stream = hex(DYNAMIC_ZLIB)
    for (end in 0 until stream.size) inflateZlib(stream, 0, end, MAX_PROFILE).shouldBeNull()
    inflateZlib(byteArrayOf(0x55) + stream, 1, stream.size + 1, MAX_PROFILE) shouldBe lettersProfile()
  }

  @Test
  fun `a corrupted huffman stream never throws`() {
    val random = Random(11)
    for (stream in listOf(hex(DYNAMIC_ZLIB), hex(FIXED_ZLIB))) {
      repeat(2000) {
        val corrupted = stream.copyOf()
        repeat(1 + it % 4) { corrupted[2 + random.nextInt(corrupted.size - 2)] = random.nextInt(256).toByte() }
        val read = inflate(corrupted, sizePrefixed = it % 2 == 0)
        (read == null || read.size <= MAX_PROFILE) shouldBe true
      }
    }
  }

  @Test
  fun `png image data keeps only the image and reads on past it through back references`() {
    val stream = hex(FIXED_ZLIB)
    inflatePngImage(stream, intArrayOf(stream.size), 100) shouldBe periodicProfile().copyOf(100)
    inflatePngImage(stream, intArrayOf(stream.size), 600) shouldBe periodicProfile()
    inflatePngImage(stream, intArrayOf(stream.size), 601).shouldBeNull()
    val long = zeroRunZlib(byteArrayOf(1, 2, 3), 100_000)
    inflatePngImage(long, intArrayOf(long.size), 2) shouldBe byteArrayOf(1, 2)
    val letters = hex(DYNAMIC_ZLIB)
    inflatePngImage(letters, intArrayOf(letters.size), 128) shouldBe lettersProfile()
  }

  @Test
  fun `png image data that ends short of the image is refused`() {
    val header = byteArrayOf(1, 2, 3)
    for (zeros in listOf(0, 1, 295)) {
      val stream = zeroRunZlib(header, zeros)
      inflatePngImage(stream, intArrayOf(stream.size), 299).shouldBeNull()
    }
    val stream = zeroRunZlib(header, 296)
    inflatePngImage(stream, intArrayOf(stream.size), 299) shouldBe header + ByteArray(296)
  }

  // libpng refuses a fault zlib meets in the read that wrote the image's last byte, and only warns about one it meets
  // in a later read or after zlib has more to write than the image.
  @Test
  fun `png image data refuses a bad checksum only in the read that ends the image`() {
    val stream = hex(FIXED_ZLIB).also { it[it.lastIndex] = (it.last() + 1).toByte() }
    inflatePngImage(stream, intArrayOf(stream.size), 600).shouldBeNull()
    inflatePngImage(stream, intArrayOf(stream.size - 4, stream.size), 600) shouldBe periodicProfile()
    inflatePngImage(stream, intArrayOf(stream.size - 2, stream.size), 600) shouldBe periodicProfile()
    inflatePngImage(stream, intArrayOf(stream.size), 599) shouldBe periodicProfile().copyOf(599)
  }

  @Test
  fun `png image data that runs out while libpng still reads is refused`() {
    val full = hex(FIXED_ZLIB)
    val cut = full.copyOf(full.size - 2)
    inflatePngImage(cut, intArrayOf(cut.size), 600).shouldBeNull()
    inflatePngImage(cut, intArrayOf(cut.size), 100).shouldBeNull()
    inflatePngImage(cut, intArrayOf(cut.size - 2, cut.size), 600) shouldBe periodicProfile()
    for (end in 0 until full.size - 4) inflatePngImage(full.copyOf(end), intArrayOf(end), 600).shouldBeNull()
  }

  @Test
  fun `png image data reads its chunks the size libpng reads them`() {
    LIBPNG_IDAT_READ_SIZE shouldBe 8192
    for ((size, accepted) in listOf(8180 to false, 8184 to true, 8185 to true)) {
      val image = ByteArray(size) { (it % 251).toByte() }
      val stream = storedZlibBlocks(image).also { it[it.lastIndex] = (it.last() + 1).toByte() }
      val read = inflatePngImage(stream, intArrayOf(stream.size), size)
      if (accepted) read shouldBe image else read.shouldBeNull()
    }
  }

  private fun inflate(
    stream: ByteArray,
    limit: Int = MAX_PROFILE,
    sizePrefixed: Boolean = false,
  ): ByteArray? = inflateZlib(stream, 0, stream.size, limit, sizePrefixed)

  // One fixed Huffman block that inflates to the literals followed by that many zero bytes.
  private fun zeroRunZlib(
    literals: ByteArray,
    zeros: Int,
  ): ByteArray {
    val writer = BitWriter()
    writer.bits(1, 1)
    writer.bits(1, 2)
    literals.forEach { writer.literal(it.toInt() and 0xFF) }
    var left = zeros
    if (left > 0) {
      writer.literal(0)
      left--
    }
    while (left >= 258) {
      writer.code(0xC5, 8)
      writer.code(0, 5)
      left -= 258
    }
    repeat(left) { writer.literal(0) }
    writer.code(0, 7)
    val adler = adler32Of(literals)
    val a = adler and 0xFFFF
    val b = ((adler ushr 16) + zeros.toLong() % 65521 * a) % 65521
    val checksum = (b shl 16) or a
    return byteArrayOf(0x78, 0xDA.toByte()) + writer.finish() +
      ByteArray(4) { (checksum ushr (24 - it * 8)).toByte() }
  }

  private class BitWriter {
    private val out = mutableListOf<Byte>()
    private var buffer = 0
    private var count = 0

    fun bits(
      value: Int,
      length: Int,
    ) {
      for (bit in 0 until length) {
        buffer = buffer or (((value shr bit) and 1) shl count)
        if (++count == 8) flush()
      }
    }

    fun code(
      code: Int,
      length: Int,
    ) {
      for (bit in length - 1 downTo 0) bits(code shr bit, 1)
    }

    fun literal(byte: Int) = if (byte < 144) code(0x30 + byte, 8) else code(0x190 + byte - 144, 9)

    fun finish(): ByteArray {
      if (count > 0) flush()
      return out.toByteArray()
    }

    private fun flush() {
      out += buffer.toByte()
      buffer = 0
      count = 0
    }
  }
}

// Level 9 zlib of lettersProfile, one dynamic Huffman block.
internal const val DYNAMIC_ZLIB =
  "78da158cc71104410c0227558489e342bf5e3d8429e0bdf773733745e26b4b221750567bf0f5f09c9c332b582af1e3d4506a6baca2a7fb10" +
    "e24547c5c77a3eed6237a41b33f807024530aa"

// Level 9 zlib of periodicProfile, one fixed Huffman block of mostly back references.
internal const val FIXED_ZLIB =
  "78da6360608a6050f5ca9fb2f31e09949a77c1d45df71949a07c0aa7ed7ec0a44e0255347dcf43660d5f12a8197b1fb168fa159340ed7bcc" +
    "aae55f329304ea099b7640e9acfd2450ec3a8165b30f3c2581d20d2a9f73f01907095470c5dc43cf39f548a02ae71d7ec1a51f420235ffc8" +
    "4b6e83d02a2229005ac10f1f"

// A 128 byte profile whose header declares its size, then letters from a small generator.
internal fun lettersProfile(): ByteArray {
  var state = 1L
  return byteArrayOf(0, 0, 0, 128.toByte()) +
    ByteArray(124) {
      state = (state * 1103515245 + 12345) and 0x7FFFFFFF
      (97 + (state shr 16) % 6).toByte()
    }
}

// A 600 byte profile whose header declares its size, then a pattern that repeats every 7 bytes.
internal fun periodicProfile(): ByteArray =
  byteArrayOf(0, 0, 0x02, 0x58) + ByteArray(596) { ((it % 7) * 37 + it / 50).toByte() }

internal fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
