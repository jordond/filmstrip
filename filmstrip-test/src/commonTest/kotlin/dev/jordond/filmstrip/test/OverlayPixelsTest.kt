package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The blend prediction and the PNG the overlay pins draw from.
 *
 * The blend is read inside the ramp, where adding and multiplying the opacity in give different codes,
 * and the PNG is read byte by byte at the header, the first pixel, the stored deflate blocks and the closing chunk.
 */
class OverlayPixelsTest {
  @Test
  fun blendsAQuarterAndThreeQuartersOfTheWayToTheOverlay() {
    OverlayPixels.sourceOver(UNDER, OVER, QUARTER) shouldBe UNDER + (OVER - UNDER) / 4
    OverlayPixels.sourceOver(UNDER, OVER, THREE_QUARTERS) shouldBe UNDER + (OVER - UNDER) * 3 / 4
  }

  @Test
  fun leavesTheFrameAloneAtNoOpacityAndCoversItAtFull() {
    OverlayPixels.sourceOver(UNDER, OVER, 0f) shouldBe UNDER
    OverlayPixels.sourceOver(UNDER, OVER, 1f) shouldBe OVER
  }

  @Test
  fun writesAPngOfTheSizeAsked() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColors.MID_GREEN)

    png.copyOfRange(0, SIGNATURE.size) shouldBe SIGNATURE
    png.int32At(WIDTH_OFFSET) shouldBe SIZE.width
    png.int32At(HEIGHT_OFFSET) shouldBe SIZE.height
  }

  @Test
  fun storesTheAuthoredColorAsTheFirstPixel() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColors.MID_GREEN)
    val pixel = png.copyOfRange(FIRST_PIXEL_OFFSET, FIRST_PIXEL_OFFSET + 4).map { it.toInt() and 0xFF }

    pixel shouldBe AuthoredColors.channelsOf(AuthoredColors.MID_GREEN) + 0xFF
  }

  @Test
  fun closesOnTheStandardEndChunk() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColors.MID_GREY)

    png.copyOfRange(png.size - IEND.size, png.size) shouldBe IEND
  }

  @Test
  fun splitsRowsOverOneStoredBlockAcrossSeveral() {
    val png = OverlayPixels.pngOf(LARGE, AuthoredColors.MID_GREEN)
    val rowBytes = (LARGE.width * 4 + 1) * LARGE.height
    val idatEnd = IDAT_DATA_OFFSET + png.int32At(IDAT_LENGTH_OFFSET)
    png.copyOfRange(IDAT_LENGTH_OFFSET + 4, IDAT_DATA_OFFSET) shouldBe "IDAT".encodeToByteArray()

    val lengths = mutableListOf<Int>()
    val lasts = mutableListOf<Boolean>()
    var at = IDAT_DATA_OFFSET + ZLIB_HEADER_BYTES
    do {
      val length = png.int16LittleEndianAt(at + 1)
      withClue("NLEN of block ${lengths.size}") { png.int16LittleEndianAt(at + 3) shouldBe (length.inv() and 0xFFFF) }
      lasts += png[at].toInt() == 1
      lengths += length
      at += STORED_BLOCK_HEADER_BYTES + length
    } while (!lasts.last() && at < idatEnd)

    withClue("$rowBytes bytes of rows fit one block") { (rowBytes > MAX_STORED_BLOCK) shouldBe true }
    val full = lengths.size - 1
    lengths shouldBe List(full) { MAX_STORED_BLOCK } + (rowBytes - MAX_STORED_BLOCK * full)
    lasts shouldBe List(full) { false } + true
    withClue("The adler32 closes the stream") { at + ADLER_BYTES shouldBe idatEnd }
  }

  @Test
  fun refusesAProfileShorterThanAnIccHeader() {
    assertFailsWith<IllegalArgumentException> { OverlayPixels.pngOf(SIZE, AuthoredColors.MID_GREY, ByteArray(20)) }
  }

  private fun ByteArray.int16LittleEndianAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() and 0xFF shl 8)

  private fun ByteArray.int32At(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
      (this[offset + 1].toInt() and 0xFF shl 16) or
      (this[offset + 2].toInt() and 0xFF shl 8) or
      (this[offset + 3].toInt() and 0xFF)

  private companion object {
    const val UNDER = 40
    const val OVER = 200
    const val QUARTER = 0.25f
    const val THREE_QUARTERS = 0.75f

    val SIZE = Size(6, 2)

    // Wider than one stored deflate block holds, so the rows take two blocks.
    val LARGE = Size(200, 100)

    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // The signature, then the IHDR length and type.
    const val WIDTH_OFFSET = 16
    const val HEIGHT_OFFSET = 20

    // The IHDR chunk ends at 33, the IDAT length and type take 8, the zlib header and stored block
    // header take 7, and the first row opens on its filter byte.
    const val FIRST_PIXEL_OFFSET = 49

    const val IDAT_LENGTH_OFFSET = 33
    const val IDAT_DATA_OFFSET = 41
    const val ZLIB_HEADER_BYTES = 2
    const val STORED_BLOCK_HEADER_BYTES = 5
    const val ADLER_BYTES = 4
    const val MAX_STORED_BLOCK = 0xFFFF

    // An empty IEND chunk, whose checksum every PNG shares.
    val IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
  }
}
