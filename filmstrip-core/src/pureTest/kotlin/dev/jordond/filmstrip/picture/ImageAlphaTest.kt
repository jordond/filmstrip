package dev.jordond.filmstrip.picture

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ImageAlphaTest {
  @Test
  fun `a png of colour type 6 or 4 carries alpha and the other colour types do not`() {
    imageHasAlphaOf(png(colourType = 6)) shouldBe true
    imageHasAlphaOf(png(colourType = 4)) shouldBe true
    imageHasAlphaOf(png(colourType = 2)) shouldBe false
    imageHasAlphaOf(png(colourType = 0)) shouldBe false
    imageHasAlphaOf(png(colourType = 3)) shouldBe false
  }

  @Test
  fun `a png with a tRNS chunk ahead of its pixels carries alpha`() {
    val palette = chunk("PLTE", ByteArray(6)) + chunk("tRNS", ByteArray(2))

    imageHasAlphaOf(png(colourType = 3, before = palette)) shouldBe true
    imageHasAlphaOf(png(colourType = 2, before = chunk("tRNS", ByteArray(6)))) shouldBe true
  }

  @Test
  fun `a tRNS chunk after the first IDAT is not looked for`() {
    imageHasAlphaOf(png(colourType = 2) + chunk("tRNS", ByteArray(6))) shouldBe false
  }

  @Test
  fun `a webp reads its alpha from the VP8X flag or the VP8L bit`() {
    imageHasAlphaOf(webp("VP8X", ByteArray(10).also { it[0] = 0x10 })) shouldBe true
    imageHasAlphaOf(webp("VP8X", ByteArray(10))) shouldBe false
    imageHasAlphaOf(webp("VP8L", byteArrayOf(0x2F, 0, 0, 0, 0x10))) shouldBe true
    imageHasAlphaOf(webp("VP8L", byteArrayOf(0x2F, 0, 0, 0, 0))) shouldBe false
    imageHasAlphaOf(webp("VP8 ", ByteArray(10))) shouldBe false
  }

  @Test
  fun `a header that is not a png or a webp or stops short reads as opaque`() {
    imageHasAlphaOf(ByteArray(0)) shouldBe false
    imageHasAlphaOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) shouldBe false
    imageHasAlphaOf(png(colourType = 6).copyOf(20)) shouldBe false
  }

  private fun png(
    colourType: Int,
    before: ByteArray = ByteArray(0),
  ): ByteArray {
    val header = ByteArray(13)
    header[3] = 1
    header[7] = 1
    header[8] = 8
    header[9] = colourType.toByte()

    return SIGNATURE + chunk("IHDR", header) + before + chunk("IDAT", ByteArray(4))
  }

  private fun chunk(
    type: String,
    data: ByteArray,
  ): ByteArray = byteArrayOf(0, 0, 0, data.size.toByte()) + type.encodeToByteArray() + data + ByteArray(4)

  private fun webp(
    chunkType: String,
    data: ByteArray,
  ): ByteArray =
    "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + chunkType.encodeToByteArray() +
      ByteArray(4) + data

  private companion object {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
