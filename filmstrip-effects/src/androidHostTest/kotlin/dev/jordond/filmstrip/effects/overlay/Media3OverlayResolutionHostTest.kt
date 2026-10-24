package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.OverlayPixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How an overlay picture is prepared for Android's decoder and where the decoder lands it.
 *
 * Every AVIF and HEIC with an `nclx` reaches the decoder with a codestream and an `nclx` that state the nearest matrix
 * the decoder reads of the picture's own reading, the box's range, no transfer, and the primaries the decoder pairs
 * with the matrix. One without an `nclx` reaches it stating its codestream's own range and transfer beside that matrix
 * and those primaries. Each lands as the codestream it reaches the decoder with says on that API level. A picture core
 * refuses reaches it as it is. [DecoderLimitHostTest] covers what a picture landed short of its file reports.
 */
class Media3OverlayResolutionHostTest {
  @Test
  fun everyHeicWithAnNclxReachesTheDecoderStatingWhatItsDecoderReads() {
    for (picture in ColourPictures.HEICS_WITH_SIGNAL_VARIANTS.filter { it.nclx != null }) {
      for (sdk in HEIC_API_LEVELS) {
        val prepared = picture.bytes().forAndroidDecoder(sdk).bytes

        assertEquals(handedOver(picture.bytes(), sdk), pictureYcbcrCoding(prepared)?.codestream, "$picture on $sdk")
        assertNull(pictureColour(prepared), "$picture still describes its colour to the decoder")
      }
    }
  }

  @Test
  fun aHeicReachesTheDecoderWithAMatrixItReadsBesideThePrimariesItPairsWithIt() {
    for (matrix in listOf(1, 2, 5, 6, 7, 8, 9, 10, 12)) {
      for (fullRange in listOf(true, false)) {
        for (sdk in HEIC_API_LEVELS) {
          val file = heicRecording(matrix, fullRange)
          val prepared = file.forAndroidDecoder(sdk).bytes

          val what = "matrix $matrix, full range $fullRange on API $sdk"
          assertEquals(handedOver(file, sdk), pictureYcbcrCoding(prepared)?.codestream, what)
        }
      }
    }
    for ((sdk, fullRange) in listOf(LATEST_API to true, 33 to false)) {
      val smpte240 = heicRecording(SMPTE_240, fullRange)
      val nearest = nearestFor(smpte240, sdk)
      val stated = CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, fullRange)
      assertEquals(stated, pictureYcbcrCoding(smpte240.forAndroidDecoder(sdk).bytes)?.codestream, "API $sdk")
    }
  }

  @Test
  fun aPictureCoreRefusesReachesTheDecoderAsItIs() {
    for (file in listOf(heicRecording(ICTCP, true), avifRecording(ICTCP, false), avifRecording(0, true))) {
      val refusal = assertNotNull(pictureYcbcrCoding(file)?.refusal)
      for (sdk in AVIF_API_LEVELS) {
        val input = file.forAndroidDecoder(sdk)

        assertSame(file, input.bytes, "API $sdk")
        assertEquals(refusal, input.refusal, "API $sdk")
        assertNull(input.landing, "API $sdk")
      }
    }
  }

  @Test
  fun aHeicWhoseCodestreamDiffersFromItsNclxOnlyInPrimariesReachesTheDecoderWithPairedPrimaries() {
    val picture = ColourPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3
    val nclx = checkNotNull(picture.nclx)
    val vui = checkNotNull(picture.vui.colour)
    assertEquals(listOf(nclx.matrix, nclx.fullRange), listOf(vui.matrix, vui.fullRange))
    assertTrue(nclx.primaries != vui.primaries)

    val prepared = picture.bytes().forAndroidDecoder(LATEST_API).bytes

    assertEquals(CodestreamSignal(BT709, UNSPECIFIED, BT709, true), pictureYcbcrCoding(prepared)?.codestream)
  }

  @Test
  fun everyAvifWithAnNclxReachesTheDecoderStatingWhatItsDecoderReads() {
    for (picture in (ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA).filter { it.nclx != null }) {
      for (sdk in AVIF_API_LEVELS) {
        val prepared = picture.bytes().forAndroidDecoder(sdk).bytes

        val handed = handedOver(picture.bytes(), sdk)
        assertEquals(handed, pictureYcbcrCoding(prepared)?.codestream, "$picture on $sdk")
        assertEquals(handed.matrix, pictureYcbcrCoding(prepared)?.matrix, "$picture's nclx on $sdk")
        assertNull(pictureColour(prepared), "$picture still describes its colour to the decoder")
      }
    }
  }

  @Test
  fun anAvifReachesTheDecoderWithAMatrixItReadsBesideThePrimariesItPairsWithIt() {
    for (matrix in listOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 12)) {
      for (fullRange in listOf(true, false)) {
        for (sdk in AVIF_API_LEVELS) {
          val file = avifRecording(matrix, fullRange)
          val prepared = file.forAndroidDecoder(sdk).bytes

          val what = "matrix $matrix, full range $fullRange on API $sdk"
          assertEquals(handedOver(file, sdk), pictureYcbcrCoding(prepared)?.codestream, what)
        }
      }
    }
    for (sdk in AVIF_API_LEVELS) {
      val bt2020Constant = avifRecording(BT2020_CONSTANT, true)
      val nearest = nearestFor(bt2020Constant, sdk)
      val prepared = bt2020Constant.forAndroidDecoder(sdk).bytes
      val stated = CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, true)
      assertEquals(stated, pictureYcbcrCoding(prepared)?.codestream, "API $sdk")
      assertEquals(nearest, pictureYcbcrCoding(prepared)?.matrix, "the nclx on API $sdk")
    }
    assertEquals(BT2020, nearestFor(avifRecording(BT2020_CONSTANT, true), 33), "constant luminance keeps its weights")
    val smpte240 = avifRecording(SMPTE_240, false).forAndroidDecoder(LATEST_API).bytes
    assertEquals(CodestreamSignal(BT601, UNSPECIFIED, SMPTE_240, false), pictureYcbcrCoding(smpte240)?.codestream)
  }

  @Test
  fun aPictureWithoutAnNclxReachesTheDecoderStatingWhatItsDecoderReadsOfItsCodestream() {
    val avifs = listOf(ColourPictures.PROFILE_DISPLAY_P3) + AvifColourPictures.CODESTREAM_COLOUR_AVIFS
    val heics =
      listOf(ColourPictures.HEIC_PROFILE_DISPLAY_P3, ColourPictures.HEIC_UNTAGGED) +
        ColourPictures.CODESTREAM_COLOUR_HEICS
    val pictures =
      avifs.filter { it.nclx == null }.map { "$it" to it.bytes() } +
        heics.filter { it.nclx == null }.map { "$it" to it.bytes() }
    for ((name, bytes) in pictures) {
      for (sdk in AVIF_API_LEVELS) {
        val prepared = bytes.forAndroidDecoder(sdk).bytes

        assertEquals(handedOver(bytes, sdk), pictureYcbcrCoding(prepared)?.codestream, "$name on API $sdk")
      }
    }
  }

  @Test
  fun aPictureThatIsNeitherAvifNorHeicReachesTheDecoderAsItIs() {
    val png = OverlayPixels.pngOf(SIDE, ColourPictures.TEXEL)
    val input = png.forAndroidDecoder(LATEST_API)

    assertSame(png, input.bytes)
    assertNull(input.landing)
    assertNull(input.refusal)
  }

  @Test
  fun aPictureWithoutAnNclxLandsAsItsCodestreamStatesIt() {
    val silentFull = ColourPictures.PROFILE_DISPLAY_P3.bytes()
    val bt709Limited = AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes()
    val heicBt709Limited = ColourPictures.HEIC_CODESTREAM_BT709_LIMITED.bytes()
    for (sdk in AVIF_API_LEVELS) {
      val decodesAs = UNSPECIFIED_MATRIX_DECODES_AS
      assertLanding(landing(decodesAs, true, sdk, avif = true), assertNotNull(silentFull.landing(sdk)), "API $sdk")
      assertLanding(landing(BT709, false, sdk, avif = true), assertNotNull(bt709Limited.landing(sdk)), "API $sdk")
      assertLanding(landing(BT709, false, sdk, avif = false), assertNotNull(heicBt709Limited.landing(sdk)), "API $sdk")
    }
    assertEquals(emptyList(), silentFull.landing(33)?.limits)
    for (limited in listOf(bt709Limited, heicBt709Limited)) {
      val limits = assertNotNull(limited.landing(33)).limits
      assertEquals(1, limits.filterIsInstance<DecoderLimit.LandedThrough>().size)
    }
    assertEquals(emptyList(), heicBt709Limited.landing(34)?.limits)
  }

  @Test
  fun aPictureLandsAsTheCodestreamItReachesTheDecoderWithStates() {
    for (sdk in AVIF_API_LEVELS) {
      val heic = assertNotNull(heicRecording(BT709, false).landing(sdk))
      assertLanding(landing(BT709, false, sdk, avif = false), heic, "API $sdk")
      val avif = assertNotNull(avifRecording(BT709, false).landing(sdk))
      assertLanding(landing(BT709, false, sdk, avif = true), avif, "API $sdk")
      val smpte240 = assertNotNull(heicRecording(SMPTE_240, true).landing(sdk))
      assertLanding(landing(SMPTE_240, true, sdk, avif = false), smpte240, "matrix 7 on API $sdk")
      val derived = assertNotNull(avifRecording(CHROMATICITY_DERIVED, true).landing(sdk))
      assertLanding(landing(BT709, true, sdk, avif = true), derived, "matrix 12 on API $sdk")
      assertNull(OverlayPixels.pngOf(SIDE, ColourPictures.TEXEL).landing(sdk))
    }
  }

  @Test
  fun aPictureWhoseCodestreamDoesNotReadLandsWithoutALimit() {
    val coding = assertNotNull(pictureYcbcrCoding(ColourPictures.PROFILE_DISPLAY_P3.bytes())).copy(codestream = null)

    for (sdk in AVIF_API_LEVELS) {
      assertNull(coding.decoderLanding(sdk, handedFullRange = null, restated = false), "API $sdk")
    }
  }

  @Test
  fun aPictureCoreCannotRewriteLandsOnlyWhereItsCodestreamStatesAMatrixItsDecoderReadsBesidePairedPrimaries() {
    val heic = ColourPictures.HEIC_CODESTREAM_BT709_LIMITED.bytes().withMoov()
    val avif = avifSequence(AvifColourPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes())
    for (sdk in AVIF_API_LEVELS) {
      val heicInput = heic.forAndroidDecoder(sdk)
      val avifInput = avif.forAndroidDecoder(sdk)
      assertEquals(pictureYcbcrCoding(heic)?.codestream, pictureYcbcrCoding(heicInput.bytes)?.codestream)
      assertEquals(pictureYcbcrCoding(avif)?.codestream, pictureYcbcrCoding(avifInput.bytes)?.codestream)

      if (sdk >= 34) {
        assertLanding(landing(BT709, false, sdk, avif = false), assertNotNull(heicInput.landing), "HEIC on API $sdk")
        assertLanding(landing(BT709, false, sdk, avif = true), assertNotNull(avifInput.landing), "AVIF on API $sdk")
      } else {
        assertNull(heicInput.landing, "HEIC on API $sdk")
        assertNull(avifInput.landing, "AVIF on API $sdk")
      }
    }
    val besideOtherPrimaries = ColourPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3.bytes().withMoov()
    val silent = ColourPictures.HEIC_NCLX_BT709_FULL.bytes().withMoov()
    val nclxDisagrees = ColourPictures.HEIC_NCLX_BT601_VUI_BT709.bytes().withMoov()
    for (file in listOf(besideOtherPrimaries, silent, nclxDisagrees)) {
      assertNull(file.forAndroidDecoder(LATEST_API).landing)
    }
  }

  @Test
  fun everyNclxStatesTheMatrixItsCodestreamStates() {
    for (matrix in listOf(1, 7, 8, 10)) {
      for (sdk in HEIC_API_LEVELS) {
        val heic = heicRecording(matrix, true)
        val prepared = heic.forAndroidDecoder(sdk).bytes

        assertEquals(nearestFor(heic, sdk), pictureYcbcrCoding(prepared)?.matrix, "HEIC matrix $matrix on API $sdk")
      }
      for (sdk in AVIF_API_LEVELS) {
        val avif = avifRecording(matrix, true)
        val prepared = avif.forAndroidDecoder(sdk).bytes

        assertEquals(nearestFor(avif, sdk), pictureYcbcrCoding(prepared)?.matrix, "AVIF matrix $matrix on API $sdk")
      }
    }
  }

  // A codestream and nclx already stating what the restate gives come back stating the same, and the picture lands as
  // they state, whether or not the decoder input counts as restated.
  @Test
  fun aPictureAlreadyStatingTheRestateLandsAsItStates() {
    val hidden = avifRecording(BT601, true).withColourDescriptionsHidden(keepNclxPrimaries = true)
    val stated = heifWithRestatedColour(hidden) { _, _, _ -> RestatedColour(BT601, BT601, nclx = true) }
    val coding = assertNotNull(pictureYcbcrCoding(stated))
    assertEquals(CodestreamSignal(BT601, UNSPECIFIED, BT601, true), coding.codestream)
    for (sdk in AVIF_API_LEVELS) {
      val described = stated.withColourDescriptionsHidden(keepNclxPrimaries = true)
      val restate = assertNotNull(restatedForDecoder(coding.reading, true, sdk, avif = true, tenBit = false))
      assertContentEquals(described, heifWithRestatedColour(described) { _, _, _ -> restate }, "API $sdk")

      val input = stated.forAndroidDecoder(sdk)

      val want = landing(BT601, true, sdk, avif = true)
      assertEquals(BT601, pictureYcbcrCoding(input.bytes)?.matrix, "API $sdk")
      assertLanding(want, assertNotNull(input.landing), "API $sdk")
      val unchanged = coding.decoderLanding(sdk, handedFullRange = true, restated = false)
      assertLanding(want, assertNotNull(unchanged), "API $sdk as stated")
    }
  }

  @Test
  fun aPictureCoreCannotRewriteWhoseNclxAgreesWithItsCodestreamLandsAsItStates() {
    val heic = heifWithExplicitCodestreamColour(heicRecording(BT709, false)).withMoov()
    val coding = assertNotNull(pictureYcbcrCoding(heic))
    assertTrue(coding.fromNclx)
    assertEquals(CodestreamSignal(BT709, SRGB_TRANSFER, BT709, false), coding.codestream)
    for (sdk in AVIF_API_LEVELS) {
      val input = heic.forAndroidDecoder(sdk)
      assertEquals(coding.codestream, pictureYcbcrCoding(input.bytes)?.codestream, "API $sdk")

      if (sdk >= 34) {
        assertLanding(landing(BT709, false, sdk, avif = false), assertNotNull(input.landing), "API $sdk")
      } else {
        assertNull(input.landing, "API $sdk")
      }
    }
  }

  // How the decoder on [sdk] lands [this] once it is prepared for it, as read does.
  private fun ByteArray.landing(sdk: Int): DecoderLanding? = forAndroidDecoder(sdk).landing

  // How the decoder on [sdk] lands an eight-bit picture recording [matrix] beside BT.709 primaries and the sRGB
  // transfer at [fullRange].
  private fun landing(
    matrix: Int,
    fullRange: Boolean,
    sdk: Int,
    avif: Boolean,
  ): DecoderLanding = assertNotNull(decoderLanding(reading(matrix), fullRange, sdk, avif, tenBit = false))

  // The matrix core finds nearest to [file]'s own reading among those its decoder on [sdk] reads at its range.
  private fun nearestFor(
    file: ByteArray,
    sdk: Int,
  ): Int {
    val coding = assertNotNull(pictureYcbcrCoding(file))
    val readable = readableMatrices(coding.fullRange, sdk, coding.avif, coding.tenBitSamples)
    return assertNotNull(coding.reading.nearestReadable(readable)).matrix
  }

  // The codestream [file] reaches the decoder on [sdk] with. The reading core gives the picture is restated to the
  // nearest matrix Android's decoder reads, beside the primaries the decoder pairs with that, at the picture's own
  // range. One with an nclx states no transfer, and one without states its codestream's own.
  private fun handedOver(
    file: ByteArray,
    sdk: Int,
  ): CodestreamSignal {
    val coding = assertNotNull(pictureYcbcrCoding(file))
    val own = assertNotNull(pictureYcbcrCoding(heifWithExplicitCodestreamColour(file))?.codestream)
    val fullRange = own.fullRange ?: false
    val restated =
      assertNotNull(restatedForDecoder(coding.reading, fullRange, sdk, coding.avif, coding.tenBitSamples))
    val transfer = if (coding.fromNclx) UNSPECIFIED else own.transfer
    return CodestreamSignal(restated.primaries, transfer, restated.matrix, fullRange)
  }

  // This picture with a moov box after everything else, which core's rewrite leaves alone.
  private fun ByteArray.withMoov(): ByteArray = this + byteArrayOf(0, 0, 0, BOX_HEADER) + "moov".encodeToByteArray()

  // [avif] branded as an image sequence, with a moov box after everything else as a sequence carries.
  private fun avifSequence(avif: ByteArray): ByteArray =
    avif.copyOf().also { "avis".encodeToByteArray().copyInto(it, BRAND) }.withMoov()

  private fun assertLanding(
    want: DecoderLanding,
    landing: DecoderLanding,
    what: Any,
  ) {
    val got = listOf(landing.matrix, landing.fullRange, landing.limits, landing.refusal)
    assertEquals(listOf(want.matrix, want.fullRange, want.limits, want.refusal), got, "$what")
  }

  private companion object {
    val SIDE = Size(4, 4)
    const val UNSPECIFIED = 2
    const val BT709 = 1
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val ICTCP = 14
    const val SRGB_TRANSFER = 13
    const val CHROMATICITY_DERIVED = 12
    const val LATEST_API = 36
    const val BOX_HEADER: Byte = 8

    // ftyp's major brand, right after the box's size and type.
    const val BRAND = 8

    // Every level Android decodes HEIC on, 28 and 29 given API 30's landing and 32 given API 31's.
    val HEIC_API_LEVELS = listOf(28, 29, 30, 31, 32, 33, 34, 35, LATEST_API)

    // Every level Android decodes AVIF on, 32 given API 31's landing.
    val AVIF_API_LEVELS = listOf(31, 32, 33, 34, 35, LATEST_API)
  }
}
