package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.pictureRoute
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicColourPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AVIF and HEIC overlays tagged with every matrix coefficients code point core reads but identity, at full and limited
 * range, each decoded at eight and twelve bits and held at its centre texel against [ycbcrToRgb].
 *
 * A matrix swscale names lands through swscale within [PICTURE_DRIFT]. YCgCo 8, constant luminance 10 and 13, and 12
 * on primaries no named matrix matches land from their planes through core's evaluator, exactly at eight bits and
 * within one code at twelve, where the sixteen-bit samples are rounded once more. Each of those is checked to land
 * elsewhere under BT.601, the matrix swscale falls back to. Identity has its own test, since it needs 4:4:4 samples.
 * ffmpeg 6.1 has no HEIF demuxer, which skips the HEIC tests as an assumption.
 */
class FfmpegPictureMatrixTest {
  @Test
  fun `an AVIF tagged 1 BT709 at full range lands where core reads it`() = avif(CicpTag(1, 13, 1, true))

  @Test
  fun `an AVIF tagged 1 BT709 at limited range lands where core reads it`() = avif(CicpTag(1, 13, 1, false))

  @Test
  fun `a HEIC tagged 1 BT709 at full range lands where core reads it`() = heic(CicpTag(1, 13, 1, true))

  @Test
  fun `a HEIC tagged 1 BT709 at limited range lands where core reads it`() = heic(CicpTag(1, 13, 1, false))

  @Test
  fun `an AVIF tagged 2 unspecified at full range lands where core reads it`() = avif(CicpTag(1, 13, 2, true))

  @Test
  fun `an AVIF tagged 2 unspecified at limited range lands where core reads it`() = avif(CicpTag(1, 13, 2, false))

  @Test
  fun `a HEIC tagged 2 unspecified at full range lands where core reads it`() = heic(CicpTag(1, 13, 2, true))

  @Test
  fun `a HEIC tagged 2 unspecified at limited range lands where core reads it`() = heic(CicpTag(1, 13, 2, false))

  @Test
  fun `an AVIF tagged 4 FCC at full range lands where core reads it`() = avif(CicpTag(1, 13, 4, true))

  @Test
  fun `an AVIF tagged 4 FCC at limited range lands where core reads it`() = avif(CicpTag(1, 13, 4, false))

  @Test
  fun `a HEIC tagged 4 FCC at full range lands where core reads it`() = heic(CicpTag(1, 13, 4, true))

  @Test
  fun `a HEIC tagged 4 FCC at limited range lands where core reads it`() = heic(CicpTag(1, 13, 4, false))

  @Test
  fun `an AVIF tagged 5 BT470BG at full range lands where core reads it`() = avif(CicpTag(1, 13, 5, true))

  @Test
  fun `an AVIF tagged 5 BT470BG at limited range lands where core reads it`() = avif(CicpTag(1, 13, 5, false))

  @Test
  fun `a HEIC tagged 5 BT470BG at full range lands where core reads it`() = heic(CicpTag(1, 13, 5, true))

  @Test
  fun `a HEIC tagged 5 BT470BG at limited range lands where core reads it`() = heic(CicpTag(1, 13, 5, false))

  @Test
  fun `an AVIF tagged 6 BT601 at full range lands where core reads it`() = avif(CicpTag(1, 13, 6, true))

  @Test
  fun `an AVIF tagged 6 BT601 at limited range lands where core reads it`() = avif(CicpTag(1, 13, 6, false))

  @Test
  fun `a HEIC tagged 6 BT601 at full range lands where core reads it`() = heic(CicpTag(1, 13, 6, true))

  @Test
  fun `a HEIC tagged 6 BT601 at limited range lands where core reads it`() = heic(CicpTag(1, 13, 6, false))

  @Test
  fun `an AVIF tagged 7 SMPTE 240M at full range lands where core reads it`() = avif(CicpTag(1, 13, 7, true))

  @Test
  fun `an AVIF tagged 7 SMPTE 240M at limited range lands where core reads it`() = avif(CicpTag(1, 13, 7, false))

  @Test
  fun `a HEIC tagged 7 SMPTE 240M at full range lands where core reads it`() = heic(CicpTag(1, 13, 7, true))

  @Test
  fun `a HEIC tagged 7 SMPTE 240M at limited range lands where core reads it`() = heic(CicpTag(1, 13, 7, false))

  @Test
  fun `an AVIF tagged 8 YCgCo at full range lands where core reads it`() = avif(CicpTag(1, 13, 8, true))

  @Test
  fun `an AVIF tagged 8 YCgCo at limited range lands where core reads it`() = avif(CicpTag(1, 13, 8, false))

  @Test
  fun `a HEIC tagged 8 YCgCo at full range lands where core reads it`() = heic(CicpTag(1, 13, 8, true))

  @Test
  fun `a HEIC tagged 8 YCgCo at limited range lands where core reads it`() = heic(CicpTag(1, 13, 8, false))

  @Test
  fun `an AVIF tagged 9 BT2020 at full range lands where core reads it`() = avif(CicpTag(9, 13, 9, true))

  @Test
  fun `an AVIF tagged 9 BT2020 at limited range lands where core reads it`() = avif(CicpTag(9, 13, 9, false))

  @Test
  fun `a HEIC tagged 9 BT2020 at full range lands where core reads it`() = heic(CicpTag(9, 13, 9, true))

  @Test
  fun `a HEIC tagged 9 BT2020 at limited range lands where core reads it`() = heic(CicpTag(9, 13, 9, false))

  @Test
  fun `an AVIF tagged 10 BT2020 CL at full range lands where core reads it`() = avif(CicpTag(9, 1, 10, true))

  @Test
  fun `an AVIF tagged 10 BT2020 CL at limited range lands where core reads it`() = avif(CicpTag(9, 1, 10, false))

  @Test
  fun `a HEIC tagged 10 BT2020 CL at full range lands where core reads it`() = heic(CicpTag(9, 1, 10, true))

  @Test
  fun `a HEIC tagged 10 BT2020 CL at limited range lands where core reads it`() = heic(CicpTag(9, 1, 10, false))

  @Test
  fun `an AVIF tagged 12 on BT709 at full range lands where core reads it`() = avif(CicpTag(1, 13, 12, true))

  @Test
  fun `an AVIF tagged 12 on BT709 at limited range lands where core reads it`() = avif(CicpTag(1, 13, 12, false))

  @Test
  fun `a HEIC tagged 12 on BT709 at full range lands where core reads it`() = heic(CicpTag(1, 13, 12, true))

  @Test
  fun `a HEIC tagged 12 on BT709 at limited range lands where core reads it`() = heic(CicpTag(1, 13, 12, false))

  @Test
  fun `an AVIF tagged 12 on BT2020 at full range lands where core reads it`() = avif(CicpTag(9, 13, 12, true))

  @Test
  fun `an AVIF tagged 12 on BT2020 at limited range lands where core reads it`() = avif(CicpTag(9, 13, 12, false))

  @Test
  fun `a HEIC tagged 12 on BT2020 at full range lands where core reads it`() = heic(CicpTag(9, 13, 12, true))

  @Test
  fun `a HEIC tagged 12 on BT2020 at limited range lands where core reads it`() = heic(CicpTag(9, 13, 12, false))

  @Test
  fun `an AVIF tagged 12 on Display P3 at full range lands where core reads it`() = avif(CicpTag(12, 13, 12, true))

  @Test
  fun `an AVIF tagged 12 on Display P3 at limited range lands where core reads it`() = avif(CicpTag(12, 13, 12, false))

  @Test
  fun `a HEIC tagged 12 on Display P3 at full range lands where core reads it`() = heic(CicpTag(12, 13, 12, true))

  @Test
  fun `a HEIC tagged 12 on Display P3 at limited range lands where core reads it`() = heic(CicpTag(12, 13, 12, false))

  @Test
  fun `an AVIF tagged 13 on BT2020 at full range lands where core reads it`() = avif(CicpTag(9, 1, 13, true))

  @Test
  fun `an AVIF tagged 13 on BT2020 at limited range lands where core reads it`() = avif(CicpTag(9, 1, 13, false))

  @Test
  fun `a HEIC tagged 13 on BT2020 at full range lands where core reads it`() = heic(CicpTag(9, 1, 13, true))

  @Test
  fun `a HEIC tagged 13 on BT2020 at limited range lands where core reads it`() = heic(CicpTag(9, 1, 13, false))

  @Test
  fun `an AVIF tagged 13 on Display P3 at full range lands where core reads it`() = avif(CicpTag(12, 13, 13, true))

  @Test
  fun `an AVIF tagged 13 on Display P3 at limited range lands where core reads it`() = avif(CicpTag(12, 13, 13, false))

  @Test
  fun `a HEIC tagged 13 on Display P3 at full range lands where core reads it`() = heic(CicpTag(12, 13, 13, true))

  @Test
  fun `a HEIC tagged 13 on Display P3 at limited range lands where core reads it`() = heic(CicpTag(12, 13, 13, false))

  // The eight-bit flat AVIF item, with the codestream whose range bit matches [nclx], and the twelve-bit AVIF.
  private fun avif(nclx: CicpTag) {
    val picture = if (nclx.fullRange) ColourPictures.NCLX_UNSPECIFIED else ColourPictures.NCLX_BT709_LIMITED
    val item = picture.thumbnail()
    val eightBit = HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
    assertLanded("AVIF $nclx", eightBit, FLAT_YCBCR, EIGHT_BITS)
    val twelveBit = AvifColourPictures.twelveBit(nclx).bytes()
    assertLanded("twelve-bit AVIF $nclx", twelveBit, AvifColourPictures.TWELVE_BIT_YCBCR, TWELVE_BITS)
  }

  // The eight-bit flat HEIC item, whose codestream signals no range or colour, and the twelve-bit HEIC.
  private fun heic(nclx: CicpTag) {
    assumeHeif()
    val item = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    val eightBit = HeifStills.still("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
    assertLanded("HEIC $nclx", eightBit, FLAT_YCBCR, EIGHT_BITS)
    val twelveBit = HeicColourPictures.twelveBit(nclx)
    assertLanded("twelve-bit HEIC $nclx", twelveBit, HeicColourPictures.TWELVE_BIT_YCBCR, TWELVE_BITS)
  }

  // [bytes] decoded and held at its centre against [ycbcrToRgb] of [ycbcr] through the coding core reads for it.
  private fun assertLanded(
    name: String,
    bytes: ByteArray,
    ycbcr: List<Int>,
    bitDepth: Int,
  ) {
    val coding = assertNotNull(pictureYcbcrCoding(bytes), name)
    assertEquals(bitDepth, coding.bitDepth, name)
    val (y, cb, cr) = ycbcr
    val expected =
      ycbcrToRgb(y, cb, cr, coding.matrix, coding.primaries, coding.transfer, coding.fullRange, bitDepth).toList()
    val planes = pictureRoute(coding) is PictureRoute.Planes
    val drift =
      if (planes) {
        val bt601 = ycbcrToRgb(y, cb, cr, BT601, coding.primaries, coding.transfer, coding.fullRange, bitDepth).toList()
        val apart = expected.indices.any { abs(expected[it] - bt601[it]) > PICTURE_DRIFT }
        assertTrue(apart, "$name reads alike as BT.601")
        if (bitDepth == EIGHT_BITS) 0 else 1
      } else {
        PICTURE_DRIFT
      }
    val decoded = decoded(bytes)
    val wide = decoded.samples is StoredSamples.Rgba16
    assertEquals(bitDepth > EIGHT_BITS, wide, "$name decoded at the wrong depth")
    val landed = centreOf(decoded)
    val near = landed.indices.all { abs(landed[it] - expected[it]) <= drift }
    assertTrue(near, "$name decoded $landed, not $expected within $drift")
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    val refused = (read as? OverlayPictureRead.Refused)?.reason ?: (read as? OverlayPictureDecode.Undrawn)?.reason
    return assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing: $refused")
  }

  // The red, green and blue of [decoded]'s centre texel in eight-bit codes.
  private fun centreOf(decoded: DecodedPicture): List<Int> {
    val at = decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2
    return when (val samples = decoded.samples) {
      is StoredSamples.Argb -> {
        listOf(samples.argb[at] shr 16, samples.argb[at] shr 8, samples.argb[at]).map { it and 0xFF }
      }
      is StoredSamples.Rgba16 -> {
        (0 until 3).map { ((samples.rgba[at * 4 + it].toInt() and SIXTEEN_BIT_MAX) / WIDE_PER_CODE).roundToInt() }
      }
    }
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")

    const val BT601 = 6
    const val EIGHT_BITS = 8
    const val TWELVE_BITS = 12
    const val SIXTEEN_BIT_MAX = 0xFFFF

    // An eight-bit code at sixteen bits.
    const val WIDE_PER_CODE = 257.0

    // The codes every sample of the eight-bit AVIF and HEIC items decodes to.
    val FLAT_YCBCR = ColourPictures.NCLX_UNSPECIFIED.ycbcr
  }
}
