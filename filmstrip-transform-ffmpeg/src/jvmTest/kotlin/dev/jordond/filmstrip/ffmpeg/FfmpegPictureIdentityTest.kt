package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.otherCodecDepthReason
import dev.jordond.filmstrip.ffmpeg.internal.pictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.ycbcrScale
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.IDENTITY_MATRIX
import dev.jordond.filmstrip.media.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicIdentityPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.landedRgb
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AVIF and HEIC stills coded with the identity matrix, whose luma, blue difference and red difference planes hold
 * green, blue and red, decoded by this ffmpeg.
 *
 * ffmpeg 6.1's dav1d wrapper hands back RGB for the identity matrix only beside BT.709 primaries and the sRGB transfer
 * and converts any other identity picture as BT.601 YCbCr, and its HEVC decoder hands back limited range identity
 * planes without stretching them, so the decoder takes the planes as they are and turns them into RGB through core's
 * evaluator. Each still is held at its middle against [landedRgb], which a BT.601 decode would miss, on BT.709 with
 * sRGB, unspecified, Display P3 and BT.2020 with PQ, at full and limited range, flat and tiled, and at ten and twelve
 * bits for AVIF and ten for HEIC within [IDENTITY_DRIFT]. An identity `nclx` on subsampled samples, which AV1 and HEVC
 * cannot code, is not drawn with core's reason, and so is an identity AVIF whose `av1C` does not say its layout and
 * depth.
 */
class FfmpegPictureIdentityTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `every identity AVIF fixture decodes its planes as green, blue and red`() {
    if (!fixture.available()) return
    for (picture in AvifColourPictures.NCLX_IDENTITY_AVIFS) assertLanded(picture.name, picture.bytes(), picture.ycbcr)
  }

  @Test
  fun `an identity AVIF lands per its nclx on any primaries, transfer and range`() {
    if (!fixture.available()) return
    val item = AvifColourPictures.NCLX_IDENTITY_444.thumbnail()
    for (nclx in NCLX_IDENTITIES) {
      val bytes = HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      assertLanded("identity $nclx", bytes, AvifColourPictures.NCLX_IDENTITY_444.ycbcr)
    }
  }

  // ffmpeg 6.1 reads a grid item's data as a coded picture and fails on it. That is the build's own limit.
  @Test
  fun `a tiled identity AVIF decodes with the grid's nclx`() {
    if (!fixture.available()) return
    assumeTiles(ColourPictures.NCLX_BT709_LIMITED.gridBytes())
    for (picture in AvifColourPictures.NCLX_IDENTITY_AVIFS) {
      assertLanded("tiled ${picture.name}", picture.gridBytes(), picture.ycbcr)
    }
    val item = AvifColourPictures.NCLX_IDENTITY_444.thumbnail()
    val limited = CicpTag(BT2020, PQ, IDENTITY_MATRIX, false)
    val tiled = HeifStills.grid("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = limited)
    assertLanded("tiled identity $limited", tiled, AvifColourPictures.NCLX_IDENTITY_444.ycbcr)
  }

  @Test
  fun `a ten bit identity AVIF keeps its depth through the merge`() {
    if (!fixture.available()) return
    for (fullRange in listOf(true, false)) {
      val nclx = CicpTag(BT2020, PQ, IDENTITY_MATRIX, fullRange)
      val bytes = HeifStills.still("avif", "av01", TEN_BIT_AV1C, TEN_BIT_CODESTREAM, AVIF_SIZE, nclx, bitDepth = 10)
      assertWideLanded("$nclx", bytes, TEN_BIT_YCBCR, bitDepth = 10)
    }
  }

  @Test
  fun `a twelve bit identity AVIF keeps its depth through the merge`() {
    if (!fixture.available()) return
    val nclx = CicpTag(BT2020, PQ, IDENTITY_MATRIX, false)
    val bytes = HeifStills.still("avif", "av01", TWELVE_BIT_AV1C, TWELVE_BIT_CODESTREAM, AVIF_SIZE, nclx, bitDepth = 12)
    assertEquals(12, assertNotNull(pictureYcbcrCoding(bytes)).bitDepth)
    assertWideLanded("$nclx", bytes, TWELVE_BIT_YCBCR, bitDepth = 12)
  }

  // ffmpeg 6.1 has no HEIF demuxer. That is the build's own limit.
  @Test
  fun `a 4 4 4 identity HEIC decodes its planes as green, blue and red at full and limited range`() {
    if (!fixture.available()) return
    assumeHeif()
    val (y, cb, cr) = HeicIdentityPictures.YCBCR
    for (nclx in HEIC_IDENTITIES) {
      val bytes = HeicIdentityPictures.bytes(nclx)
      val stretches = !landedRgb(bytes, HeicIdentityPictures.YCBCR).contentEquals(intArrayOf(cr, y, cb))
      assertEquals(!nclx.fullRange, stretches, "$nclx")
      assertLanded("HEIC $nclx", bytes, HeicIdentityPictures.YCBCR)
    }
  }

  // The grid goes through the complex graph, merge and stretch included. ffmpeg 6.1 has neither HEIF nor tile grids.
  @Test
  fun `a tiled identity HEIC decodes with the grid's nclx`() {
    if (!fixture.available()) return
    assumeHeif()
    assumeTiles(ColourPictures.HEIC_NCLX_BT709_LIMITED.gridBytes())
    val item = HeicIdentityPictures.ITEM
    for (nclx in HEIC_IDENTITIES) {
      val grid = HeifStills.grid("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      assertLanded("tiled HEIC $nclx", grid, HeicIdentityPictures.YCBCR)
    }
  }

  @Test
  fun `a ten bit identity HEIC keeps its depth through the merge`() {
    if (!fixture.available()) return
    assumeHeif()
    for (nclx in HEIC_IDENTITIES) {
      val bytes = HeicIdentityPictures.tenBitBytes(nclx)
      assertEquals(10, assertNotNull(pictureYcbcrCoding(bytes)).bitDepth)
      assertWideLanded("HEIC $nclx", bytes, HeicIdentityPictures.TEN_BIT_YCBCR, bitDepth = 10)
    }
  }

  @Test
  fun `an identity AVIF or HEIC is decoded from its planes and another codec's identity is refused`() {
    for (codec in listOf(CodecKind.Av1, CodecKind.Hevc)) {
      for (fullRange in listOf(true, false)) {
        val route = assertIs<PictureRoute.Planes>(pictureRoute(identity(fullRange, bitDepth = 8).copy(codec = codec)))
        assertEquals("${MERGE}yuv444p", route.filter, "$codec full range $fullRange")
        assertEquals(8, route.bitDepth)
      }
    }
    val bt601 = identity(fullRange = false, bitDepth = 8).copy(matrix = BT601)
    assertEquals(ycbcrScale(bt601), convertedFilter(bt601))
    assertEquals(ycbcrScale(bt601), convertedFilter(bt601.copy(codec = CodecKind.Hevc)))
    val other = YcbcrCoding(BT601, false, CodecKind.Other, codestream = null)
    assertTrue(other.colourKnown)
    assertEquals(ycbcrScale(other), convertedFilter(other))
    assertTrue(ycbcrScale(other).startsWith("scale="))
    val otherIdentity = other.copy(matrix = IDENTITY_MATRIX, fullRange = true)
    assertFalse(otherIdentity.identityNot444)
    assertEquals(
      otherCodecDepthReason(IDENTITY_MATRIX),
      assertIs<PictureRoute.Refused>(pictureRoute(otherIdentity)).reason,
    )
    assertEquals(PASS_THROUGH, convertedFilter(null))
  }

  @Test
  fun `a picture whose colour core does not know goes through untouched whatever its codec and matrix`() {
    for (codec in listOf(CodecKind.Av1, CodecKind.Hevc)) {
      for (matrix in listOf(IDENTITY_MATRIX, BT601)) {
        val unknown = identity(fullRange = false, bitDepth = 8).copy(matrix = matrix, codec = codec, fromNclx = false)
        assertFalse(unknown.colourKnown, "$codec $matrix")
        assertEquals(PASS_THROUGH, convertedFilter(unknown), "$codec $matrix")
      }
    }
  }

  @Test
  fun `an identity nclx on subsampled samples is refused with core's reason`() {
    val avif = ColourPictures.NCLX_DISPLAY_P3.thumbnail()
    val heic = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    for (nclx in listOf(CicpTag(1, 13, IDENTITY_MATRIX, true), CicpTag(BT2020, PQ, IDENTITY_MATRIX, false))) {
      for ((brand, item) in listOf("avif" to avif, "heic" to heic)) {
        val bytes = HeifStills.still(brand, item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
        val read = decoder.decode(ImageSource.ofBytes(bytes))
        assertEquals(IDENTITY_NOT_444_REASON, assertIs<OverlayPictureDecode.Undrawn>(read, "$brand $nclx").reason)
      }
    }
  }

  @Test
  fun `an identity AVIF whose av1C does not say its layout and depth is refused with core's reason`() {
    val item = AvifColourPictures.NCLX_IDENTITY_444.thumbnail()
    val bytes = HeifStills.still("avif", item.itemType, SHORT_AV1C, item.codestream, item.size, nclx = identityNclx)
    val coding = assertNotNull(pictureYcbcrCoding(bytes))
    assertNull(coding.bitDepth)
    assertNull(coding.chromaFormat)
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    assertEquals(IDENTITY_NOT_444_REASON, assertIs<OverlayPictureDecode.Undrawn>(read).reason)
  }

  @Test
  fun `the identity matrix merges the planes at the picture's depth`() {
    for (fullRange in listOf(true, false)) {
      for ((bitDepth, format) in listOf(8 to "yuv444p", 10 to "yuv444p10le", 12 to "yuv444p12le")) {
        val route = assertIs<PictureRoute.Planes>(pictureRoute(identity(fullRange, bitDepth)))
        assertEquals("$MERGE$format", route.filter, "$bitDepth bits full range $fullRange")
        assertEquals(bitDepth, route.bitDepth)
      }
    }
  }

  // [bytes] decoded and held at its middle against where core's coding of it puts [ycbcr], after checking a BT.601
  // decode, the one ffmpeg 6.1 falls back to, would land it elsewhere.
  private fun assertLanded(
    name: String,
    bytes: ByteArray,
    ycbcr: List<Int>,
  ) {
    val expected = landedRgb(bytes, ycbcr)
    val (y, cb, cr) = ycbcr
    val coding = assertNotNull(pictureYcbcrCoding(bytes), name)
    val bt601 = ycbcrToRgb(y, cb, cr, BT601, coding.primaries, coding.transfer, coding.fullRange)
    assertTrue(!expected.contentEquals(bt601), "$name reads alike as BT.601")
    val decoded = decoded(bytes)
    val argb = assertIs<StoredSamples.Argb>(decoded.samples, "$name decoded deeper than eight bits").argb
    val centre = argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2]
    val read = intArrayOf(centre shr 16, centre shr 8, centre).map { it and 0xFF }
    assertContentEquals(expected.toList(), read, name)
  }

  // [bytes], deeper than eight bits, decoded and held at its middle within IDENTITY_DRIFT of where core's coding of it
  // puts [ycbcr], after checking a BT.601 decode would land it further off than PICTURE_DRIFT.
  private fun assertWideLanded(
    name: String,
    bytes: ByteArray,
    ycbcr: List<Int>,
    bitDepth: Int,
  ) {
    val coding = assertNotNull(pictureYcbcrCoding(bytes), name)
    val (y, cb, cr) = ycbcr
    val primaries = coding.primaries
    val transfer = coding.transfer
    val expected = ycbcrToRgb(y, cb, cr, coding.matrix, primaries, transfer, coding.fullRange, bitDepth).toList()
    val bt601 = ycbcrToRgb(y, cb, cr, BT601, primaries, transfer, coding.fullRange, bitDepth).toList()
    assertTrue(expected.indices.any { abs(expected[it] - bt601[it]) > PICTURE_DRIFT }, "$name reads alike as BT.601")
    val decoded = decoded(bytes)
    val rgba = assertIs<StoredSamples.Rgba16>(decoded.samples, "$name decoded at eight bits").rgba
    val at = (decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2) * 4
    val read = (0 until 3).map { ((rgba[at + it].toInt() and SIXTEEN_BIT_MAX) / WIDE_PER_CODE).roundToInt() }
    val near = read.indices.all { abs(read[it] - expected[it]) <= IDENTITY_DRIFT }
    assertTrue(near, "$name decoded $read, not $expected")
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing: $read")
  }

  private fun identity(
    fullRange: Boolean,
    bitDepth: Int,
  ) = YcbcrCoding(IDENTITY_MATRIX, fullRange, CodecKind.Av1, codestream = null, bitDepth = bitDepth, chromaFormat = 3)

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val identityNclx = CicpTag(BT2020, PQ, IDENTITY_MATRIX, true)
    const val MERGE = "mergeplanes=map0s=0:map0p=0:map1s=0:map1p=1:map2s=0:map2p=2:format="
    val HEIC_IDENTITIES = listOf(CicpTag(1, 13, IDENTITY_MATRIX, true), CicpTag(BT2020, PQ, IDENTITY_MATRIX, false))
    val AVIF_SIZE = Size(8, 8)

    const val BT601 = 6
    const val BT2020 = 9
    const val PQ = 16

    // Identity on primaries and transfers other than BT.709 with sRGB, which ffmpeg 6.1 decodes as BT.601, and on
    // BT.709 with sRGB at limited range, which AV1 cannot state, so the codestream stays silent.
    val NCLX_IDENTITIES =
      listOf(
        CicpTag(BT2020, PQ, IDENTITY_MATRIX, true),
        CicpTag(BT2020, PQ, IDENTITY_MATRIX, false),
        CicpTag(2, 2, IDENTITY_MATRIX, false),
        CicpTag(12, 13, IDENTITY_MATRIX, true),
        CicpTag(1, 13, IDENTITY_MATRIX, false),
      )

    // A ten-bit code at sixteen bits. A BT.601 decode moves these texels by twenty codes or more.
    const val WIDE_PER_CODE = 257.0

    // The ten-bit codestream comes from ffmpeg 6.1.1 with libaom, on Ubuntu 24.04:
    //   ffmpeg -f lavfi -i "color=black:s=8x8,format=yuv444p10le,geq=lum=600:cb=300:cr=900" -frames:v 1 \
    //     -color_range pc -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f obu ten.obu
    // without the leading temporal delimiter. Its sequence header is profile 1 at ten bits with full range and no
    // colour description, and decoding it back gives 600, 300 and 900 at every sample.
    val TEN_BIT_YCBCR = listOf(600, 300, 900)
    val TEN_BIT_CODESTREAM = hex("0a053808bf6e903214100000000ff88ee1f7cb9ac19a970152a7393a20")

    // The av1C for it: marker and version, profile 1 at level 0, then high_bitdepth with 4:4:4 samples.
    val TEN_BIT_AV1C = hex("0000000c" + "61763143" + "81204000")

    // The twelve-bit codestream comes from the same ffmpeg and libaom:
    //   ffmpeg -f lavfi -i "color=black:s=8x8,format=yuv444p12le,geq=lum=2000:cb=1100:cr=3000" -frames:v 1 \
    //     -color_range tv -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f obu twelve.obu
    // without the leading temporal delimiter. Its sequence header is profile 2 at twelve bits with 4:4:4 samples,
    // limited range and no colour description, and decoding it back gives 2000, 1100 and 3000 at every sample.
    val TWELVE_BIT_YCBCR = listOf(2000, 1100, 3000)
    val TWELVE_BIT_CODESTREAM = hex("0a055808bf6f023214100000000ffa401aa831ea32d4b37ca7b2e5602c")

    // The av1C for it: marker and version, profile 2 at level 0, then high_bitdepth and twelve_bit with 4:4:4 samples.
    val TWELVE_BIT_AV1C = hex("0000000c" + "61763143" + "81406000")

    // An av1C cut off after its marker and profile byte, so the byte holding the depth is missing.
    val SHORT_AV1C = hex("0000000a" + "61763143" + "8120")

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
