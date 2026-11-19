package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.render.SAMPLE_BYTES
import dev.jordond.filmstrip.webcodecs.internal.render.interleavedTenBitCodes
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The pack pass's planes spread out to three codes a pixel, read against a pack whose every sample is a code of its
 * own.
 */
class InterleavedTenBitCodesTest {
  @Test
  fun eachPixelTakesItsLumaAndTheChromaOfItsBlock() {
    val codes = interleavedTenBitCodes(numberedPack(), WIDTH, HEIGHT)

    codes.size shouldBe WIDTH * HEIGHT * 3
    pixel(codes, 0, 0) shouldBe listOf(lumaCode(0, 0), CB_BASE, CR_BASE)
    // A pixel inside the frame on an odd row and column, so a block counted from the wrong corner or a stride taken as
    // the chroma width reads another sample.
    pixel(codes, 5, 3) shouldBe listOf(lumaCode(5, 3), chromaCode(CB_BASE, 2, 1), chromaCode(CR_BASE, 2, 1))
    pixel(codes, WIDTH - 1, HEIGHT - 1) shouldBe
      listOf(lumaCode(WIDTH - 1, HEIGHT - 1), chromaCode(CB_BASE, 3, 2), chromaCode(CR_BASE, 3, 2))
  }

  @Test
  fun aCodeUsesBothItsBytes() {
    val packed = ByteArray(WIDTH * SAMPLE_BYTES * 2 * HEIGHT)
    packed[0] = 0xFF.toByte()
    packed[1] = 0x03

    interleavedTenBitCodes(packed, WIDTH, HEIGHT)[0] shouldBe 1023.toShort()
  }

  // Luma, then Cb and Cr each in rows of a full stride, half of which a chroma row fills, little endian.
  private fun numberedPack(): ByteArray {
    val stride = WIDTH * SAMPLE_BYTES
    val packed = ByteArray(stride * 2 * HEIGHT)

    fun put(
      offset: Int,
      code: Int,
    ) {
      packed[offset] = (code and 0xFF).toByte()
      packed[offset + 1] = (code shr 8).toByte()
    }
    for (y in 0 until HEIGHT) for (x in 0 until WIDTH) put(y * stride + x * SAMPLE_BYTES, lumaCode(x, y))
    val lumaBytes = stride * HEIGHT
    val chromaBytes = stride * (HEIGHT / 2)
    for (y in 0 until HEIGHT / 2) {
      for (x in 0 until WIDTH / 2) {
        put(lumaBytes + y * stride + x * SAMPLE_BYTES, chromaCode(CB_BASE, x, y))
        put(lumaBytes + chromaBytes + y * stride + x * SAMPLE_BYTES, chromaCode(CR_BASE, x, y))
      }
    }
    return packed
  }

  private fun pixel(
    codes: ShortArray,
    x: Int,
    y: Int,
  ): List<Int> = List(3) { codes[(y * WIDTH + x) * 3 + it].toInt() }

  private fun lumaCode(
    x: Int,
    y: Int,
  ): Int = LUMA_BASE + y * WIDTH + x

  private fun chromaCode(
    base: Int,
    x: Int,
    y: Int,
  ): Int = base + y * (WIDTH / 2) + x

  private companion object {
    const val WIDTH = 8
    const val HEIGHT = 6
    const val LUMA_BASE = 64
    const val CB_BASE = 300
    const val CR_BASE = 700
  }
}
