package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.RestatedColor
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.picture.withColorDescriptionsHidden
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

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
    for (picture in ColorPictures.HEICS_WITH_SIGNAL_VARIANTS.filter { it.nclx != null }) {
      for (sdk in HEIC_API_LEVELS) {
        val prepared = picture.bytes().forAndroidDecoder(sdk).bytes

        withClue("$picture on $sdk") {
          pictureYcbcrCoding(prepared)?.codestream shouldBe handedOver(picture.bytes(), sdk)
        }
        withClue("$picture still describes its color to the decoder") { pictureColor(prepared).shouldBeNull() }
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
          withClue(what) { pictureYcbcrCoding(prepared)?.codestream shouldBe handedOver(file, sdk) }
        }
      }
    }
    for ((sdk, fullRange) in listOf(LATEST_API to true, 33 to false)) {
      val smpte240 = heicRecording(SMPTE_240, fullRange)
      val nearest = nearestFor(smpte240, sdk)
      val stated = CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, fullRange)
      withClue("API $sdk") { pictureYcbcrCoding(smpte240.forAndroidDecoder(sdk).bytes)?.codestream shouldBe stated }
    }
  }

  @Test
  fun aPictureCoreRefusesReachesTheDecoderAsItIs() {
    for (file in listOf(heicRecording(ICTCP, true), avifRecording(ICTCP, false), avifRecording(0, true))) {
      val refusal = pictureYcbcrCoding(file)?.refusal.shouldNotBeNull()
      for (sdk in AVIF_API_LEVELS) {
        val input = file.forAndroidDecoder(sdk)

        withClue("API $sdk") { input.bytes shouldBeSameInstanceAs file }
        withClue("API $sdk") { input.refusal shouldBe refusal }
        withClue("API $sdk") { input.conversion.shouldBeNull() }
      }
    }
  }

  @Test
  fun aHeicWhoseCodestreamDiffersFromItsNclxOnlyInPrimariesReachesTheDecoderWithPairedPrimaries() {
    val picture = ColorPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3
    val nclx = checkNotNull(picture.nclx)
    val vui = checkNotNull(picture.vui.color)
    listOf(vui.matrix, vui.fullRange) shouldBe listOf(nclx.matrix, nclx.fullRange)
    (nclx.primaries != vui.primaries) shouldBe true

    val prepared = picture.bytes().forAndroidDecoder(LATEST_API).bytes

    pictureYcbcrCoding(prepared)?.codestream shouldBe CodestreamSignal(BT709, UNSPECIFIED, BT709, true)
  }

  @Test
  fun everyAvifWithAnNclxReachesTheDecoderStatingWhatItsDecoderReads() {
    for (picture in (ColorPictures.AVIFS + ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA).filter { it.nclx != null }) {
      for (sdk in AVIF_API_LEVELS) {
        val prepared = picture.bytes().forAndroidDecoder(sdk).bytes

        val handed = handedOver(picture.bytes(), sdk)
        withClue("$picture on $sdk") { pictureYcbcrCoding(prepared)?.codestream shouldBe handed }
        withClue("$picture's nclx on $sdk") { pictureYcbcrCoding(prepared)?.matrix shouldBe handed.matrix }
        withClue("$picture still describes its color to the decoder") { pictureColor(prepared).shouldBeNull() }
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
          withClue(what) { pictureYcbcrCoding(prepared)?.codestream shouldBe handedOver(file, sdk) }
        }
      }
    }
    for (sdk in AVIF_API_LEVELS) {
      val bt2020Constant = avifRecording(BT2020_CONSTANT, true)
      val nearest = nearestFor(bt2020Constant, sdk)
      val prepared = bt2020Constant.forAndroidDecoder(sdk).bytes
      val stated = CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, true)
      withClue("API $sdk") { pictureYcbcrCoding(prepared)?.codestream shouldBe stated }
      withClue("the nclx on API $sdk") { pictureYcbcrCoding(prepared)?.matrix shouldBe nearest }
    }
    withClue("constant luminance keeps its weights") {
      nearestFor(avifRecording(BT2020_CONSTANT, true), 33) shouldBe BT2020
    }
    val smpte240 = avifRecording(SMPTE_240, false).forAndroidDecoder(LATEST_API).bytes
    pictureYcbcrCoding(smpte240)?.codestream shouldBe CodestreamSignal(BT601, UNSPECIFIED, SMPTE_240, false)
  }

  @Test
  fun aPictureWithoutAnNclxReachesTheDecoderStatingWhatItsDecoderReadsOfItsCodestream() {
    val avifs = listOf(ColorPictures.PROFILE_DISPLAY_P3) + AvifColorPictures.CODESTREAM_COLOR_AVIFS
    val heics =
      listOf(ColorPictures.HEIC_PROFILE_DISPLAY_P3, ColorPictures.HEIC_UNTAGGED) +
        ColorPictures.CODESTREAM_COLOR_HEICS
    val pictures =
      avifs.filter { it.nclx == null }.map { "$it" to it.bytes() } +
        heics.filter { it.nclx == null }.map { "$it" to it.bytes() }
    for ((name, bytes) in pictures) {
      for (sdk in AVIF_API_LEVELS) {
        val prepared = bytes.forAndroidDecoder(sdk).bytes

        withClue("$name on API $sdk") { pictureYcbcrCoding(prepared)?.codestream shouldBe handedOver(bytes, sdk) }
      }
    }
  }

  @Test
  fun aPictureThatIsNeitherAvifNorHeicReachesTheDecoderAsItIs() {
    val png = OverlayPixels.pngOf(SIDE, ColorPictures.TEXEL)
    val input = png.forAndroidDecoder(LATEST_API)

    input.bytes shouldBeSameInstanceAs png
    input.conversion.shouldBeNull()
    input.refusal.shouldBeNull()
  }

  @Test
  fun aPictureWithoutAnNclxLandsAsItsCodestreamStatesIt() {
    val silentFull = ColorPictures.PROFILE_DISPLAY_P3.bytes()
    val bt709Limited = AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes()
    val heicBt709Limited = ColorPictures.HEIC_CODESTREAM_BT709_LIMITED.bytes()
    for (sdk in AVIF_API_LEVELS) {
      val decodesAs = UNSPECIFIED_MATRIX_DECODES_AS
      assertConversion(
        conversion(decodesAs, true, sdk, avif = true),
        silentFull.conversion(sdk).shouldNotBeNull(),
        "API $sdk",
      )
      assertConversion(
        conversion(BT709, false, sdk, avif = true),
        bt709Limited.conversion(sdk).shouldNotBeNull(),
        "API $sdk",
      )
      assertConversion(
        conversion(BT709, false, sdk, avif = false),
        heicBt709Limited.conversion(sdk).shouldNotBeNull(),
        "API $sdk",
      )
    }
    silentFull.conversion(33)?.limits shouldBe emptyList()
    for (limited in listOf(bt709Limited, heicBt709Limited)) {
      val limits = limited.conversion(33).shouldNotBeNull().limits
      limits.filterIsInstance<DecoderLimit.LandedThrough>().size shouldBe 1
    }
    heicBt709Limited.conversion(34)?.limits shouldBe emptyList()
  }

  @Test
  fun aPictureLandsAsTheCodestreamItReachesTheDecoderWithStates() {
    for (sdk in AVIF_API_LEVELS) {
      val heic = heicRecording(BT709, false).conversion(sdk).shouldNotBeNull()
      assertConversion(conversion(BT709, false, sdk, avif = false), heic, "API $sdk")
      val avif = avifRecording(BT709, false).conversion(sdk).shouldNotBeNull()
      assertConversion(conversion(BT709, false, sdk, avif = true), avif, "API $sdk")
      val smpte240 = heicRecording(SMPTE_240, true).conversion(sdk).shouldNotBeNull()
      assertConversion(conversion(SMPTE_240, true, sdk, avif = false), smpte240, "matrix 7 on API $sdk")
      val derived = avifRecording(CHROMATICITY_DERIVED, true).conversion(sdk).shouldNotBeNull()
      assertConversion(conversion(BT709, true, sdk, avif = true), derived, "matrix 12 on API $sdk")
      OverlayPixels.pngOf(SIDE, ColorPictures.TEXEL).conversion(sdk).shouldBeNull()
    }
  }

  @Test
  fun aPictureWhoseCodestreamDoesNotReadLandsWithoutALimit() {
    val coding = pictureYcbcrCoding(ColorPictures.PROFILE_DISPLAY_P3.bytes()).shouldNotBeNull().copy(codestream = null)

    for (sdk in AVIF_API_LEVELS) {
      withClue("API $sdk") { coding.decoderConversionOf(sdk, handedFullRange = null, restated = false).shouldBeNull() }
    }
  }

  @Test
  fun aPictureCoreCannotRewriteLandsOnlyWhereItsCodestreamStatesAMatrixItsDecoderReadsBesidePairedPrimaries() {
    val heic = ColorPictures.HEIC_CODESTREAM_BT709_LIMITED.bytes().withMoov()
    val avif = avifSequence(AvifColorPictures.AVIF_CODESTREAM_BT709_LIMITED.bytes())
    for (sdk in AVIF_API_LEVELS) {
      val heicInput = heic.forAndroidDecoder(sdk)
      val avifInput = avif.forAndroidDecoder(sdk)
      pictureYcbcrCoding(heicInput.bytes)?.codestream shouldBe pictureYcbcrCoding(heic)?.codestream
      pictureYcbcrCoding(avifInput.bytes)?.codestream shouldBe pictureYcbcrCoding(avif)?.codestream

      if (sdk >= 34) {
        assertConversion(
          conversion(BT709, false, sdk, avif = false),
          heicInput.conversion.shouldNotBeNull(),
          "HEIC on API $sdk",
        )
        assertConversion(
          conversion(BT709, false, sdk, avif = true),
          avifInput.conversion.shouldNotBeNull(),
          "AVIF on API $sdk",
        )
      } else {
        withClue("HEIC on API $sdk") { heicInput.conversion.shouldBeNull() }
        withClue("AVIF on API $sdk") { avifInput.conversion.shouldBeNull() }
      }
    }
    val besideOtherPrimaries = ColorPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3.bytes().withMoov()
    val silent = ColorPictures.HEIC_NCLX_BT709_FULL.bytes().withMoov()
    val nclxDisagrees = ColorPictures.HEIC_NCLX_BT601_VUI_BT709.bytes().withMoov()
    for (file in listOf(besideOtherPrimaries, silent, nclxDisagrees)) {
      file.forAndroidDecoder(LATEST_API).conversion.shouldBeNull()
    }
  }

  @Test
  fun everyNclxStatesTheMatrixItsCodestreamStates() {
    for (matrix in listOf(1, 7, 8, 10)) {
      for (sdk in HEIC_API_LEVELS) {
        val heic = heicRecording(matrix, true)
        val prepared = heic.forAndroidDecoder(sdk).bytes

        withClue("HEIC matrix $matrix on API $sdk") {
          pictureYcbcrCoding(prepared)?.matrix shouldBe nearestFor(heic, sdk)
        }
      }
      for (sdk in AVIF_API_LEVELS) {
        val avif = avifRecording(matrix, true)
        val prepared = avif.forAndroidDecoder(sdk).bytes

        withClue("AVIF matrix $matrix on API $sdk") {
          pictureYcbcrCoding(prepared)?.matrix shouldBe nearestFor(avif, sdk)
        }
      }
    }
  }

  // A codestream and nclx already stating what the restate gives come back stating the same, and the picture lands as
  // they state, whether or not the decoder input counts as restated.
  @Test
  fun aPictureAlreadyStatingTheRestateLandsAsItStates() {
    val hidden = avifRecording(BT601, true).withColorDescriptionsHidden(keepNclxPrimaries = true)
    val restated = heifWithRestatedColor(hidden) { _, _, _ -> RestatedColor(BT601, BT601, nclx = true) }
    val stated = restated.shouldBeInstanceOf<RestatedHeif.Restated>().bytes
    val coding = pictureYcbcrCoding(stated).shouldNotBeNull()
    coding.codestream shouldBe CodestreamSignal(BT601, UNSPECIFIED, BT601, true)
    for (sdk in AVIF_API_LEVELS) {
      val described = stated.withColorDescriptionsHidden(keepNclxPrimaries = true)
      val restate = restatedForDecoder(coding.reading, true, sdk, avif = true, tenBit = false).shouldNotBeNull()
      val again = heifWithRestatedColor(described) { _, _, _ -> restate }
      withClue("API $sdk") {
        withClue("API $sdk") { again.shouldBeInstanceOf<RestatedHeif.Restated>() }.bytes shouldBeSameInstanceAs
          described
      }

      val input = stated.forAndroidDecoder(sdk)

      val want = conversion(BT601, true, sdk, avif = true)
      withClue("API $sdk") { pictureYcbcrCoding(input.bytes)?.matrix shouldBe BT601 }
      assertConversion(want, input.conversion.shouldNotBeNull(), "API $sdk")
      val unchanged = coding.decoderConversionOf(sdk, handedFullRange = true, restated = false)
      assertConversion(want, unchanged.shouldNotBeNull(), "API $sdk as stated")
    }
  }

  @Test
  fun aPictureCoreCannotRewriteWhoseNclxAgreesWithItsCodestreamLandsAsItStates() {
    val stated = heifWithExplicitCodestreamColor(heicRecording(BT709, false))
    val heic = stated.shouldBeInstanceOf<UnturnedHeif.Handed>().bytes.withMoov()
    val coding = pictureYcbcrCoding(heic).shouldNotBeNull()
    coding.fromNclx shouldBe true
    coding.codestream shouldBe CodestreamSignal(BT709, SRGB_TRANSFER, BT709, false)
    for (sdk in AVIF_API_LEVELS) {
      val input = heic.forAndroidDecoder(sdk)
      withClue("API $sdk") { pictureYcbcrCoding(input.bytes)?.codestream shouldBe coding.codestream }

      if (sdk >= 34) {
        assertConversion(conversion(BT709, false, sdk, avif = false), input.conversion.shouldNotBeNull(), "API $sdk")
      } else {
        withClue("API $sdk") { input.conversion.shouldBeNull() }
      }
    }
  }

  // How the decoder on [sdk] lands [this] once it is prepared for it, as read does.
  private fun ByteArray.conversion(sdk: Int): DecoderConversion? = forAndroidDecoder(sdk).conversion

  // How the decoder on [sdk] lands an eight-bit picture recording [matrix] beside BT.709 primaries and the sRGB
  // transfer at [fullRange].
  private fun conversion(
    matrix: Int,
    fullRange: Boolean,
    sdk: Int,
    avif: Boolean,
  ): DecoderConversion = decoderConversionOf(reading(matrix), fullRange, sdk, avif, tenBit = false).shouldNotBeNull()

  // The matrix core finds nearest to [file]'s own reading among those its decoder on [sdk] reads at its range.
  private fun nearestFor(
    file: ByteArray,
    sdk: Int,
  ): Int {
    val coding = pictureYcbcrCoding(file).shouldNotBeNull()
    val readable = readableMatrices(coding.fullRange, sdk, coding.avif, coding.tenBitSamples)
    return coding.reading
      .nearestReadable(readable)
      .shouldNotBeNull()
      .matrix
  }

  // The codestream [file] reaches the decoder on [sdk] with. The reading core gives the picture is restated to the
  // nearest matrix Android's decoder reads, beside the primaries the decoder pairs with that, at the picture's own
  // range. One with an nclx states no transfer, and one without states its codestream's own.
  private fun handedOver(
    file: ByteArray,
    sdk: Int,
  ): CodestreamSignal {
    val coding = pictureYcbcrCoding(file).shouldNotBeNull()
    val stated = heifWithExplicitCodestreamColor(file).shouldBeInstanceOf<UnturnedHeif.Handed>().bytes
    val own = pictureYcbcrCoding(stated)?.codestream.shouldNotBeNull()
    val fullRange = own.fullRange ?: false
    val restated =
      restatedForDecoder(coding.reading, fullRange, sdk, coding.avif, coding.tenBitSamples).shouldNotBeNull()
    val transfer = if (coding.fromNclx) UNSPECIFIED else own.transfer
    return CodestreamSignal(restated.primaries, transfer, restated.matrix, fullRange)
  }

  // This picture with a moov box after everything else, which core's rewrite leaves alone.
  private fun ByteArray.withMoov(): ByteArray = this + byteArrayOf(0, 0, 0, BOX_HEADER) + "moov".encodeToByteArray()

  // [avif] branded as an image sequence, with a moov box after everything else as a sequence carries.
  private fun avifSequence(avif: ByteArray): ByteArray =
    avif.copyOf().also { "avis".encodeToByteArray().copyInto(it, BRAND) }.withMoov()

  private fun assertConversion(
    want: DecoderConversion,
    conversion: DecoderConversion,
    what: Any,
  ) {
    val got = listOf(conversion.matrix, conversion.fullRange, conversion.limits, conversion.refusal)
    withClue("$what") { got shouldBe listOf(want.matrix, want.fullRange, want.limits, want.refusal) }
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
