package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The blend prediction and the PNG the overlay pins draw from.
 *
 * The blend is read inside the ramp, where adding and multiplying the opacity in give different codes,
 * and the PNG is read byte by byte at the header, the first pixel and the closing chunk.
 */
class OverlayPixelsTest {
  @Test
  fun blendsAQuarterAndThreeQuartersOfTheWayToTheOverlay() {
    assertEquals(UNDER + (OVER - UNDER) / 4, OverlayPixels.sourceOver(UNDER, OVER, QUARTER))
    assertEquals(UNDER + (OVER - UNDER) * 3 / 4, OverlayPixels.sourceOver(UNDER, OVER, THREE_QUARTERS))
  }

  @Test
  fun leavesTheFrameAloneAtNoOpacityAndCoversItAtFull() {
    assertEquals(UNDER, OverlayPixels.sourceOver(UNDER, OVER, 0f))
    assertEquals(OVER, OverlayPixels.sourceOver(UNDER, OVER, 1f))
  }

  @Test
  fun writesAPngOfTheSizeAsked() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColours.MID_GREEN)

    assertContentEquals(SIGNATURE, png.copyOfRange(0, SIGNATURE.size))
    assertEquals(SIZE.width, png.int32At(WIDTH_OFFSET))
    assertEquals(SIZE.height, png.int32At(HEIGHT_OFFSET))
  }

  @Test
  fun storesTheAuthoredColourAsTheFirstPixel() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColours.MID_GREEN)
    val pixel = png.copyOfRange(FIRST_PIXEL_OFFSET, FIRST_PIXEL_OFFSET + 4).map { it.toInt() and 0xFF }

    assertEquals(AuthoredColours.channelsOf(AuthoredColours.MID_GREEN) + 0xFF, pixel)
  }

  @Test
  fun closesOnTheStandardEndChunk() {
    val png = OverlayPixels.pngOf(SIZE, AuthoredColours.MID_GREY)

    assertContentEquals(IEND, png.copyOfRange(png.size - IEND.size, png.size))
  }

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

    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // The signature, then the IHDR length and type.
    const val WIDTH_OFFSET = 16
    const val HEIGHT_OFFSET = 20

    // The IHDR chunk ends at 33, the IDAT length and type take 8, the zlib header and stored block
    // header take 7, and the first row opens on its filter byte.
    const val FIRST_PIXEL_OFFSET = 49

    // An empty IEND chunk, whose checksum every PNG shares.
    val IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
  }
}
