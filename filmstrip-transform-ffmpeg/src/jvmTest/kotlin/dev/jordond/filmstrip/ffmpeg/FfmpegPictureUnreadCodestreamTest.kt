package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.ycbcrScale
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.landedRgb
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An AVIF with no `nclx` whose sequence header core does not read, decoded by this ffmpeg.
 *
 * Its header is the BT.709 limited one of [AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED] with a padding byte that
 * is not zero after its trailing bits. Core gives up on it, while dav1d only checks trailing bits in strict mode.
 * Core's coding of it holds the BT.601 limited stand-ins of a silent codestream, so the picture must go through
 * untouched and land where the decoder reads its header, not as BT.601 limited.
 */
class FfmpegPictureUnreadCodestreamTest {
  private val fixture = FfmpegExportFixture()
  private val picture = AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED
  private val item = picture.thumbnail()

  private fun still(codestream: ByteArray): ByteArray =
    HeifStills.still("avif", item.itemType, item.configuration, codestream, item.size)

  @Test
  fun `an AVIF whose codestream core cannot read goes through untouched`() {
    val unread = assertNotNull(pictureYcbcrCoding(still(padded(item.codestream))))
    assertEquals(CodecKind.Av1, unread.codec)
    assertNull(unread.codestream)
    assertFalse(unread.colourKnown)
    assertEquals(PASS_THROUGH, convertedFilter(unread))
    val read = assertNotNull(pictureYcbcrCoding(still(item.codestream)))
    assertTrue(read.colourKnown)
    assertEquals(ycbcrScale(read), convertedFilter(read))
  }

  @Test
  fun `an AVIF whose codestream core cannot read lands where its decoder reads it`() {
    if (!fixture.available()) return
    val expected = landedRgb(still(item.codestream), picture.ycbcr).toList()
    val coding = assertNotNull(pictureYcbcrCoding(still(item.codestream)))
    val (y, cb, cr) = picture.ycbcr
    val standIn = ycbcrToRgb(y, cb, cr, BT601, coding.primaries, coding.transfer, fullRange = false).toList()
    val apart = expected.indices.any { abs(expected[it] - standIn[it]) > PICTURE_DRIFT }
    assertTrue(apart, "BT.709 reads alike as BT.601")
    val read = FfmpegPictureDecoder("ffmpeg").decode(ImageSource.ofBytes(still(padded(item.codestream))))
    val decoded = assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing: $read")
    val argb = assertIs<StoredSamples.Argb>(decoded.samples).argb
    val centre = argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2]
    val landed = intArrayOf(centre shr 16, centre shr 8, centre).map { it and 0xFF }
    val near = landed.indices.all { abs(landed[it] - expected[it]) <= PICTURE_DRIFT }
    assertTrue(near, "decoded $landed, not $expected")
  }

  // The codestream with a padding byte of 1 after its leading sequence header's trailing bits, its size one larger.
  private fun padded(codestream: ByteArray): ByteArray {
    assertEquals(SEQUENCE_HEADER_WITH_SIZE, codestream[0].toInt())
    val size = codestream[1].toInt()
    assertTrue(size in 1 until LEB128_CONTINUES, "a header of $size bytes needs a wider size field")
    val header = byteArrayOf(codestream[0], (size + 1).toByte()) + codestream.copyOfRange(2, 2 + size) + 1
    return header + codestream.copyOfRange(2 + size, codestream.size)
  }

  private companion object {
    const val SEQUENCE_HEADER_WITH_SIZE = 0x0A
    const val LEB128_CONTINUES = 0x7F

    // Read as BT.601 limited, this texel moves nine codes, well past PICTURE_DRIFT.
    const val BT601 = 6
  }
}
