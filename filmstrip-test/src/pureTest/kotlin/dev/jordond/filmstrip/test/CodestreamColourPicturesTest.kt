package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks the stills whose codestream says how they land: core reads their codestream's own signal without an `nclx`,
 * states it explicitly when the codestream leaves the matrix out, and states the `nclx` of each identity AVIF and of
 * the identity HEICs, eight and ten bits and tiled, as matrix 0. The twelve-bit AVIF reads its depth and its `nclx`
 * range, whole and tiled, and the twelve-bit HEIC reads its depth and states its `nclx` in its codestream.
 */
class CodestreamColourPicturesTest {
  private val silentLimited = CodestreamSignal(null, null, null, false)
  private val bt709Limited = CodestreamSignal(BT709_PRIMARIES, SRGB_TRANSFER, BT709, false)
  private val pqLimited = CodestreamSignal(BT2020_PRIMARIES, PQ_TRANSFER, BT2020, false)

  private fun nclxLess(): List<Pair<String, () -> ByteArray>> =
    listOf(
      AvifColourPictures.AVIF_CODESTREAM_LIMITED,
      AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED,
      AvifColourPictures.AVIF_CODESTREAM_PQ_LIMITED,
    ).flatMap { listOf("$it" to { it.bytes() }, "tiled $it" to { it.gridBytes() }) } +
      ColourPictures.CODESTREAM_COLOUR_HEICS.flatMap {
        listOf("$it" to { it.bytes() }, "tiled $it" to { it.gridBytes() })
      }

  @Test
  fun everyNclxLessStillLandsWithItsCodestreamSignal() {
    val expected =
      mapOf(
        AvifColourPictures.AVIF_CODESTREAM_LIMITED.name to
          YcbcrCoding(2, false, CodecKind.Av1, silentLimited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED.name to
          YcbcrCoding(1, false, CodecKind.Av1, bt709Limited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        AvifColourPictures.AVIF_CODESTREAM_PQ_LIMITED.name to
          YcbcrCoding(BT2020, false, CodecKind.Av1, pqLimited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        ColourPictures.HEIC_CODESTREAM_BT709_LIMITED.name to
          YcbcrCoding(1, false, CodecKind.Hevc, bt709Limited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
      )
    for ((name, bytes) in nclxLess()) {
      assertEquals(expected[name.removePrefix("tiled ")], pictureYcbcrCoding(bytes()), name)
      assertNull(pictureColour(bytes()), "$name colour")
    }
    assertEquals(HevcVui.LimitedRangeBt709, ColourPictures.HEIC_CODESTREAM_BT709_LIMITED.vui)
  }

  @Test
  fun aSilentMatrixIsStatedAsBt601AndLandsWhereItDid() {
    val picture = AvifColourPictures.AVIF_CODESTREAM_LIMITED
    for (bytes in listOf(picture.bytes(), picture.gridBytes())) {
      val rewritten = heifWithExplicitCodestreamColour(bytes)
      assertFalse(rewritten.contentEquals(bytes), "a silent codestream was not rewritten")
      val coding = pictureYcbcrCoding(rewritten)
      assertEquals(CodestreamSignal(2, 2, UNSPECIFIED_MATRIX_DECODES_AS, false), coding?.codestream)
      assertEquals(UNSPECIFIED_MATRIX_DECODES_AS, coding?.matrix)
      val landed = landedRgb(bytes, picture.ycbcr)
      assertContentEquals(landed, landedRgb(rewritten, picture.ycbcr))
      assertSame(rewritten, heifWithExplicitCodestreamColour(rewritten))
    }
  }

  @Test
  fun aCodestreamThatStatesItsColourIsLeftAlone() {
    val avif = AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED
    val heic = ColourPictures.HEIC_CODESTREAM_BT709_LIMITED
    for (bytes in listOf(avif.bytes(), avif.gridBytes(), heic.bytes(), heic.gridBytes())) {
      assertSame(bytes, heifWithExplicitCodestreamColour(bytes))
    }
  }

  @Test
  fun landedRgbFollowsTheCodestreamMatrixAndRange() {
    val (y, cb, cr) = AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED.ycbcr
    val bt709 = landedRgb(AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes(), listOf(y, cb, cr))
    assertContentEquals(ycbcrToRgb(y, cb, cr, BT709, BT709_PRIMARIES, SRGB_TRANSFER, false), bt709)
    val silent = landedRgb(AvifColourPictures.AVIF_CODESTREAM_LIMITED.bytes(), listOf(y, cb, cr))
    assertContentEquals(ycbcrToRgb(y, cb, cr, UNSPECIFIED_MATRIX_DECODES_AS, UNSPECIFIED, UNSPECIFIED, false), silent)
    assertFalse(bt709.contentEquals(silent), "the matrix makes no difference to the flat colour")
    val heic = ColourPictures.HEIC_CODESTREAM_BT709_LIMITED
    assertContentEquals(bt709, landedRgb(heic.bytes(), heic.ycbcr))
  }

  @Test
  fun everyIdentityAvifReadsItsNclxAndStatesItInItsCodestream() {
    val identities = AvifColourPictures.NCLX_IDENTITY_AVIFS
    assertEquals(listOf("1/13/0 full", "2/2/0 full"), identities.map { it.nclx.toString() })
    for (picture in identities) {
      val nclx = checkNotNull(picture.nclx)
      val bytes = picture.bytes()
      val silent = CodestreamSignal(null, null, null, true)
      val coding =
        YcbcrCoding(
          matrix = 0,
          fullRange = true,
          codec = CodecKind.Av1,
          codestream = silent,
          fromNclx = true,
          bitDepth = 8,
          chromaFormat = 3,
          primaries = nclx.primaries,
          transfer = nclx.transfer,
        )
      assertEquals(coding, pictureYcbcrCoding(bytes), "$picture")
      assertFalse(coding.identityNot444, "$picture")
      val rewritten = heifWithExplicitCodestreamColour(bytes)
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, true)
      assertEquals(coding.copy(codestream = stated), pictureYcbcrCoding(rewritten), "$picture")
      assertSame(rewritten, heifWithExplicitCodestreamColour(rewritten), "$picture")
      val (y, cb, cr) = picture.ycbcr
      assertContentEquals(intArrayOf(cr, y, cb), landedRgb(bytes, picture.ycbcr), "$picture")
      assertContentEquals(intArrayOf(cr, y, cb), landedRgb(rewritten, picture.ycbcr), "$picture")
      val av1c = picture.thumbnail().configuration
      assertEquals(listOf(0x81, 0x20, 0, 0), av1c.takeLast(4).map { it.toInt() and 0xFF }, "$picture")
    }
    val nclxLess = listOf(AvifColourPictures.AVIF_CODESTREAM_LIMITED, AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED)
    assertEquals(nclxLess + identities.first(), AvifColourPictures.CODESTREAM_COLOUR_AVIFS)
  }

  @Test
  fun theIdentityHeicReadsItsNclxAndStatesItInItsCodestream() {
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      val bytes = HeicIdentityPictures.bytes(nclx)
      val silent = CodestreamSignal(null, null, null, null)
      val coding =
        YcbcrCoding(
          matrix = 0,
          fullRange = nclx.fullRange,
          codec = CodecKind.Hevc,
          codestream = silent,
          fromNclx = true,
          bitDepth = 8,
          chromaFormat = 3,
          primaries = nclx.primaries,
          transfer = nclx.transfer,
        )
      assertEquals(coding, pictureYcbcrCoding(bytes), "$nclx")
      assertFalse(coding.identityNot444, "$nclx")
      val rewritten = heifWithExplicitCodestreamColour(bytes)
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      assertEquals(coding.copy(codestream = stated), pictureYcbcrCoding(rewritten), "$nclx")
      assertSame(rewritten, heifWithExplicitCodestreamColour(rewritten), "$nclx")
      val (y, cb, cr) = HeicIdentityPictures.YCBCR
      val expected = ycbcrToRgb(y, cb, cr, 0, nclx.primaries, nclx.transfer, nclx.fullRange)
      assertContentEquals(expected, landedRgb(rewritten, HeicIdentityPictures.YCBCR), "$nclx")
      if (nclx.fullRange) assertContentEquals(intArrayOf(cr, y, cb), expected, "$nclx")
    }
  }

  @Test
  fun theTenBitIdentityHeicReadsItsDepthAndStatesItsNclxInItsCodestream() {
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      val bytes = HeicIdentityPictures.tenBitBytes(nclx)
      val silent = CodestreamSignal(null, null, null, null)
      val coding =
        YcbcrCoding(
          matrix = 0,
          fullRange = nclx.fullRange,
          codec = CodecKind.Hevc,
          codestream = silent,
          fromNclx = true,
          bitDepth = 10,
          chromaFormat = 3,
          primaries = nclx.primaries,
          transfer = nclx.transfer,
        )
      assertEquals(coding, pictureYcbcrCoding(bytes), "$nclx")
      val rewritten = heifWithExplicitCodestreamColour(bytes)
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      assertEquals(coding.copy(codestream = stated), pictureYcbcrCoding(rewritten), "$nclx")
      assertSame(rewritten, heifWithExplicitCodestreamColour(rewritten), "$nclx")
    }
  }

  @Test
  fun theTwelveBitAvifReadsItsDepthAndItsNclxRange() {
    for (full in listOf(true, false)) {
      val picture = AvifColourPictures.twelveBit(CicpTag(1, 13, 1, full))
      val silent = CodestreamSignal(null, null, null, full)
      val coding =
        YcbcrCoding(
          matrix = 1,
          fullRange = full,
          codec = CodecKind.Av1,
          codestream = silent,
          fromNclx = true,
          bitDepth = 12,
          chromaFormat = 1,
          primaries = 1,
          transfer = 13,
        )
      assertEquals(coding, pictureYcbcrCoding(picture.bytes()), "$picture")
      assertEquals(coding, pictureYcbcrCoding(picture.gridBytes()), "tiled $picture")
      val av1c = picture.thumbnail().configuration
      assertEquals(listOf(0x81, 0x40, 0x6C, 0), av1c.takeLast(4).map { it.toInt() and 0xFF }, "$picture")
    }
  }

  @Test
  fun theTwelveBitHeicReadsItsDepthAndStatesItsNclxInItsCodestream() {
    for (nclx in listOf(CicpTag(1, 13, 1, true), CicpTag(1, 13, 6, false))) {
      val bytes = HeicColourPictures.twelveBit(nclx)
      val silent = CodestreamSignal(null, null, null, null)
      val coding =
        YcbcrCoding(
          matrix = nclx.matrix,
          fullRange = nclx.fullRange,
          codec = CodecKind.Hevc,
          codestream = silent,
          fromNclx = true,
          bitDepth = 12,
          chromaFormat = 1,
          primaries = nclx.primaries,
          transfer = nclx.transfer,
        )
      assertEquals(coding, pictureYcbcrCoding(bytes), "$nclx")
      val rewritten = heifWithExplicitCodestreamColour(bytes)
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, nclx.matrix, nclx.fullRange)
      assertEquals(coding.copy(codestream = stated), pictureYcbcrCoding(rewritten), "$nclx")
    }
  }

  @Test
  fun theTiledIdentityHeicStatesItsNclxInEveryTile() {
    val item = HeicIdentityPictures.ITEM
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      val grid = HeifStills.grid("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      val coding = assertNotNull(pictureYcbcrCoding(grid), "$nclx")
      assertEquals(3, coding.chromaFormat, "$nclx")
      assertFalse(coding.identityNot444, "$nclx")
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      val rewritten = heifWithExplicitCodestreamColour(grid)
      assertEquals(coding.copy(codestream = stated), pictureYcbcrCoding(rewritten), "$nclx")
    }
  }

  @Test
  fun anIdentityNclxOnTheSubsampledStillsIsRefused() {
    val avif = ColourPictures.NCLX_DISPLAY_P3.thumbnail()
    val heic = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      for ((name, item) in listOf("avif" to avif, "heic" to heic)) {
        val bytes = HeifStills.still(name, item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
        val coding = checkNotNull(pictureYcbcrCoding(bytes))
        assertEquals(1, coding.chromaFormat, "$name $nclx")
        assertTrue(coding.identityNot444, "$name $nclx")
      }
    }
  }

  private companion object {
    const val BT709 = 1
    const val BT709_PRIMARIES = 1
    const val SRGB_TRANSFER = 13
    const val UNSPECIFIED = 2
    const val BT2020 = 9
    const val BT2020_PRIMARIES = 9
    const val PQ_TRANSFER = 16
  }
}
