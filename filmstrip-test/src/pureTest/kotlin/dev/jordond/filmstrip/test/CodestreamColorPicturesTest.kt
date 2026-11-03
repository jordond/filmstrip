package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Checks the stills whose codestream says how they land: core reads their codestream's own signal without an `nclx`,
 * states it explicitly when the codestream leaves the matrix out, and states the `nclx` of each identity AVIF and of
 * the identity HEICs, eight and ten bits and tiled, as matrix 0. The twelve-bit AVIF reads its depth and its `nclx`
 * range, whole and tiled, and the twelve-bit HEIC reads its depth and states its `nclx` in its codestream.
 */
class CodestreamColorPicturesTest {
  private val silentLimited = CodestreamSignal(null, null, null, false)
  private val bt709Limited = CodestreamSignal(BT709_PRIMARIES, SRGB_TRANSFER, BT709, false)
  private val pqLimited = CodestreamSignal(BT2020_PRIMARIES, PQ_TRANSFER, BT2020, false)

  private fun nclxLess(): List<Pair<String, () -> ByteArray>> =
    listOf(
      AvifColorPictures.AVIF_CODESTREAM_LIMITED,
      AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED,
      AvifColorPictures.AVIF_CODESTREAM_PQ_LIMITED,
    ).flatMap { listOf("$it" to { it.bytes() }, "tiled $it" to { it.gridBytes() }) } +
      ColorPictures.CODESTREAM_COLOR_HEICS.flatMap {
        listOf("$it" to { it.bytes() }, "tiled $it" to { it.gridBytes() })
      }

  @Test
  fun everyNclxLessStillLandsWithItsCodestreamSignal() {
    val expected =
      mapOf(
        AvifColorPictures.AVIF_CODESTREAM_LIMITED.name to
          YcbcrCoding(2, false, CodecKind.Av1, silentLimited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED.name to
          YcbcrCoding(1, false, CodecKind.Av1, bt709Limited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        AvifColorPictures.AVIF_CODESTREAM_PQ_LIMITED.name to
          YcbcrCoding(BT2020, false, CodecKind.Av1, pqLimited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
        ColorPictures.HEIC_CODESTREAM_BT709_LIMITED.name to
          YcbcrCoding(1, false, CodecKind.Hevc, bt709Limited, fromNclx = false, bitDepth = 8, chromaFormat = 1),
      )
    for ((name, bytes) in nclxLess()) {
      withClue(name) { pictureYcbcrCoding(bytes()) shouldBe expected[name.removePrefix("tiled ")] }
      withClue("$name color") { pictureColor(bytes()).shouldBeNull() }
    }
    ColorPictures.HEIC_CODESTREAM_BT709_LIMITED.vui shouldBe HevcVui.LimitedRangeBt709
  }

  @Test
  fun aSilentMatrixIsStatedAsBt601AndLandsWhereItDid() {
    val picture = AvifColorPictures.AVIF_CODESTREAM_LIMITED
    for (bytes in listOf(picture.bytes(), picture.gridBytes())) {
      val rewritten = heifWithExplicitCodestreamColor(bytes).handed
      withClue("a silent codestream was not rewritten") { rewritten.contentEquals(bytes) shouldBe false }
      val coding = pictureYcbcrCoding(rewritten)
      coding?.codestream shouldBe CodestreamSignal(2, 2, UNSPECIFIED_MATRIX_DECODES_AS, false)
      coding?.matrix shouldBe UNSPECIFIED_MATRIX_DECODES_AS
      val landed = landedRgb(bytes, picture.ycbcr)
      landedRgb(rewritten, picture.ycbcr) shouldBe landed
      heifWithExplicitCodestreamColor(rewritten).handed shouldBeSameInstanceAs rewritten
    }
  }

  @Test
  fun aCodestreamThatStatesItsColorIsLeftAlone() {
    val avif = AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED
    val heic = ColorPictures.HEIC_CODESTREAM_BT709_LIMITED
    for (bytes in listOf(avif.bytes(), avif.gridBytes(), heic.bytes(), heic.gridBytes())) {
      heifWithExplicitCodestreamColor(bytes).handed shouldBeSameInstanceAs bytes
    }
  }

  @Test
  fun landedRgbFollowsTheCodestreamMatrixAndRange() {
    val (y, cb, cr) = AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED.ycbcr
    val bt709 = landedRgb(AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes(), listOf(y, cb, cr))
    bt709 shouldBe ycbcrToRgb(y, cb, cr, BT709, BT709_PRIMARIES, SRGB_TRANSFER, false)
    val silent = landedRgb(AvifColorPictures.AVIF_CODESTREAM_LIMITED.bytes(), listOf(y, cb, cr))
    silent shouldBe ycbcrToRgb(y, cb, cr, UNSPECIFIED_MATRIX_DECODES_AS, UNSPECIFIED, UNSPECIFIED, false)
    withClue("the matrix makes no difference to the flat color") { bt709.contentEquals(silent) shouldBe false }
    val heic = ColorPictures.HEIC_CODESTREAM_BT709_LIMITED
    landedRgb(heic.bytes(), heic.ycbcr) shouldBe bt709
  }

  @Test
  fun everyIdentityAvifReadsItsNclxAndStatesItInItsCodestream() {
    val identities = AvifColorPictures.NCLX_IDENTITY_AVIFS
    identities.map { it.nclx.toString() } shouldBe listOf("1/13/0 full", "2/2/0 full")
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
      withClue("$picture") { pictureYcbcrCoding(bytes) shouldBe coding }
      withClue("$picture") { coding.identityNot444 shouldBe false }
      val rewritten = heifWithExplicitCodestreamColor(bytes).handed
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, true)
      withClue("$picture") { pictureYcbcrCoding(rewritten) shouldBe coding.copy(codestream = stated) }
      withClue("$picture") { heifWithExplicitCodestreamColor(rewritten).handed shouldBeSameInstanceAs rewritten }
      val (y, cb, cr) = picture.ycbcr
      withClue("$picture") { landedRgb(bytes, picture.ycbcr) shouldBe intArrayOf(cr, y, cb) }
      withClue("$picture") { landedRgb(rewritten, picture.ycbcr) shouldBe intArrayOf(cr, y, cb) }
      val av1c = picture.thumbnail().configuration
      withClue("$picture") { av1c.takeLast(4).map { it.toInt() and 0xFF } shouldBe listOf(0x81, 0x20, 0, 0) }
    }
    val nclxLess = listOf(AvifColorPictures.AVIF_CODESTREAM_LIMITED, AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED)
    AvifColorPictures.CODESTREAM_COLOR_AVIFS shouldBe nclxLess + identities.first()
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
      withClue("$nclx") { pictureYcbcrCoding(bytes) shouldBe coding }
      withClue("$nclx") { coding.identityNot444 shouldBe false }
      val rewritten = heifWithExplicitCodestreamColor(bytes).handed
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      withClue("$nclx") { pictureYcbcrCoding(rewritten) shouldBe coding.copy(codestream = stated) }
      withClue("$nclx") { heifWithExplicitCodestreamColor(rewritten).handed shouldBeSameInstanceAs rewritten }
      val (y, cb, cr) = HeicIdentityPictures.YCBCR
      val expected = ycbcrToRgb(y, cb, cr, 0, nclx.primaries, nclx.transfer, nclx.fullRange)
      withClue("$nclx") { landedRgb(rewritten, HeicIdentityPictures.YCBCR) shouldBe expected }
      if (nclx.fullRange) withClue("$nclx") { expected shouldBe intArrayOf(cr, y, cb) }
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
      withClue("$nclx") { pictureYcbcrCoding(bytes) shouldBe coding }
      val rewritten = heifWithExplicitCodestreamColor(bytes).handed
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      withClue("$nclx") { pictureYcbcrCoding(rewritten) shouldBe coding.copy(codestream = stated) }
      withClue("$nclx") { heifWithExplicitCodestreamColor(rewritten).handed shouldBeSameInstanceAs rewritten }
    }
  }

  @Test
  fun theTwelveBitAvifReadsItsDepthAndItsNclxRange() {
    for (full in listOf(true, false)) {
      val picture = AvifColorPictures.twelveBit(CicpTag(1, 13, 1, full))
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
      withClue("$picture") { pictureYcbcrCoding(picture.bytes()) shouldBe coding }
      withClue("tiled $picture") { pictureYcbcrCoding(picture.gridBytes()) shouldBe coding }
      val av1c = picture.thumbnail().configuration
      withClue("$picture") { av1c.takeLast(4).map { it.toInt() and 0xFF } shouldBe listOf(0x81, 0x40, 0x6C, 0) }
    }
  }

  @Test
  fun theTwelveBitHeicReadsItsDepthAndStatesItsNclxInItsCodestream() {
    for (nclx in listOf(CicpTag(1, 13, 1, true), CicpTag(1, 13, 6, false))) {
      val bytes = HeicColorPictures.twelveBit(nclx)
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
      withClue("$nclx") { pictureYcbcrCoding(bytes) shouldBe coding }
      val rewritten = heifWithExplicitCodestreamColor(bytes).handed
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, nclx.matrix, nclx.fullRange)
      withClue("$nclx") { pictureYcbcrCoding(rewritten) shouldBe coding.copy(codestream = stated) }
    }
  }

  @Test
  fun theTiledIdentityHeicStatesItsNclxInEveryTile() {
    val item = HeicIdentityPictures.ITEM
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      val grid = HeifStills.grid("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
      val coding = withClue("$nclx") { pictureYcbcrCoding(grid).shouldNotBeNull() }
      withClue("$nclx") { coding.chromaFormat shouldBe 3 }
      withClue("$nclx") { coding.identityNot444 shouldBe false }
      val stated = CodestreamSignal(nclx.primaries, nclx.transfer, 0, nclx.fullRange)
      val rewritten = heifWithExplicitCodestreamColor(grid).handed
      withClue("$nclx") { pictureYcbcrCoding(rewritten) shouldBe coding.copy(codestream = stated) }
    }
  }

  @Test
  fun anIdentityNclxOnTheSubsampledStillsIsRefused() {
    val avif = ColorPictures.NCLX_DISPLAY_P3.thumbnail()
    val heic = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
    for (nclx in listOf(CicpTag(1, 13, 0, true), CicpTag(9, 16, 0, false))) {
      for ((name, item) in listOf("avif" to avif, "heic" to heic)) {
        val bytes = HeifStills.still(name, item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
        val coding = checkNotNull(pictureYcbcrCoding(bytes))
        withClue("$name $nclx") { coding.chromaFormat shouldBe 1 }
        withClue("$name $nclx") { coding.identityNot444 shouldBe true }
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
