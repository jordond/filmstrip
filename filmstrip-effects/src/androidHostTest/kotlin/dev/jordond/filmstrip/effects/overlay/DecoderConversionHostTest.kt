package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.heif.RestatedColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How a picture's depth and codec decide what Android's decoder is handed and what it reports.
 *
 * Only a ten-bit picture takes the ten-bit answers. A twelve-bit AVIF is refused by name from API 31 to 35 and lands
 * as an eight-bit one from API 36, reporting any limit its landing names and then the cut to eight bits, or refused
 * when its landing is past the bound. A twelve-bit HEIC the decoder hands back no frame for is named from API 28,
 * where Android first decodes HEIC. Every restate states the nearest matrix in the `nclx` too.
 */
class DecoderConversionHostTest {
  @Test
  fun onlyTenBitSamplesTakeTheTenBitAnswers() {
    coding(CodecKind.Av1, depth = 8).tenBitSamples shouldBe false
    coding(CodecKind.Av1, depth = 10).tenBitSamples shouldBe true
    coding(CodecKind.Av1, depth = 12).tenBitSamples shouldBe false
    coding(CodecKind.Av1, depth = null).tenBitSamples shouldBe false
    coding(CodecKind.Hevc, depth = 10).tenBitSamples shouldBe true
  }

  @Test
  fun onlyAnAv1PictureIsAnAvifAndOnlyATenBitOneIsATenBitAvif() {
    coding(CodecKind.Av1, depth = 8).avif shouldBe true
    coding(CodecKind.Hevc, depth = 8).avif shouldBe false
    coding(CodecKind.Av1, depth = 10).tenBitAvif shouldBe true
    for (depth in listOf(8, 12, null)) {
      withClue("depth $depth") {
        coding(CodecKind.Av1, depth).tenBitAvif shouldBe false
      }
    }
    coding(CodecKind.Hevc, depth = 10).tenBitAvif shouldBe false
  }

  @Test
  fun onlyATwelveBitHevcPictureIsATwelveBitHeic() {
    coding(CodecKind.Hevc, depth = 12).twelveBitHeic shouldBe true
    for (depth in listOf(8, 10, null)) {
      withClue("depth $depth") {
        coding(CodecKind.Hevc, depth).twelveBitHeic shouldBe false
      }
    }
    coding(CodecKind.Av1, depth = 12).twelveBitHeic shouldBe false
    val fixture = HeicColorPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, BT709, true))
    pictureYcbcrCoding(fixture).shouldNotBeNull().twelveBitHeic shouldBe true
  }

  @Test
  fun onlyATwelveBitAv1PictureIsATwelveBitAvif() {
    coding(CodecKind.Av1, depth = 8).twelveBitAvif shouldBe false
    coding(CodecKind.Av1, depth = 10).twelveBitAvif shouldBe false
    coding(CodecKind.Av1, depth = 12).twelveBitAvif shouldBe true
    coding(CodecKind.Av1, depth = null).twelveBitAvif shouldBe false
    coding(CodecKind.Hevc, depth = 12).twelveBitAvif shouldBe false
    val fixture = pictureYcbcrCoding(twelveBit(BT709, true)).shouldNotBeNull()
    fixture.twelveBitAvif shouldBe true
    fixture.tenBitSamples shouldBe false
  }

  @Test
  fun aTwelveBitAvifIsRefusedByNameBelowApi36() {
    for (full in listOf(true, false)) {
      val coding = pictureYcbcrCoding(twelveBit(BT709, full))
      for (sdk in listOf(31, 32, 33, 34, 35)) {
        withClue("API $sdk") { refusalBeforeDecoder(coding, sdk) shouldBe DecoderRefusal.AvifTwelveBit.message }
      }
      for (sdk in listOf(24, 30, 36, 37)) withClue("API $sdk") { refusalBeforeDecoder(coding, sdk).shouldBeNull() }
      noFrameRefusal(coding, 30) shouldBe DecoderRefusal.NoFrame
    }
  }

  @Test
  fun onlyATwelveBitAvifOrAnIdentityPictureOnSubsampledSamplesIsRefusedBeforeTheDecoder() {
    for (depth in listOf(8, 10, null)) {
      withClue("$depth") {
        refusalBeforeDecoder(coding(CodecKind.Av1, depth), 33).shouldBeNull()
      }
    }
    for (sdk in listOf(28, 33, 36)) {
      withClue("$sdk") {
        refusalBeforeDecoder(coding(CodecKind.Hevc, depth = 12), sdk).shouldBeNull()
      }
    }
    refusalBeforeDecoder(null, 33).shouldBeNull()
    val identity = YcbcrCoding(0, true, CodecKind.Av1, codestream = null, bitDepth = 12, chromaFormat = 1)
    refusalBeforeDecoder(identity, 36) shouldBe IDENTITY_NOT_444_REASON
    for (matrix in listOf(3, 11, 14, 15)) {
      val refused = YcbcrCoding(matrix, true, CodecKind.Hevc, codestream = null, bitDepth = 8, chromaFormat = 1)
      withClue("matrix $matrix") { refusalBeforeDecoder(refused, 33) shouldBe refused.refusal.shouldNotBeNull() }
    }
  }

  @Test
  fun onlyATenBitAvifOnApi33NamesTheFrameItsDecoderHandsBackNone() {
    val tenBit = coding(CodecKind.Av1, depth = 10)
    noFrameRefusal(tenBit, 33) shouldBe DecoderRefusal.AvifTenBitNoFrame
    for (sdk in listOf(31, 32, 34, 35, 36)) {
      withClue("API $sdk") { noFrameRefusal(tenBit, sdk) shouldBe DecoderRefusal.NoFrame }
    }
    for (depth in listOf(8, 12, null)) {
      withClue("depth $depth") { noFrameRefusal(coding(CodecKind.Av1, depth), 33) shouldBe DecoderRefusal.NoFrame }
    }
    noFrameRefusal(coding(CodecKind.Hevc, depth = 10), 33) shouldBe DecoderRefusal.NoFrame
    noFrameRefusal(coding(CodecKind.Hevc, depth = 12), 33) shouldBe DecoderRefusal.HeicTwelveBitNoFrame
    noFrameRefusal(null, 33) shouldBe DecoderRefusal.NoFrame
  }

  @Test
  fun aTwelveBitHeicIsNamedFromApi28AndIsAPlainNoFrameBelow() {
    for (full in listOf(true, false)) {
      val coding = pictureYcbcrCoding(HeicColorPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, BT709, full)))
      for (sdk in listOf(28, 29, 30, 31, 32, 33, 34, 35, 36)) {
        withClue("API $sdk") { noFrameRefusal(coding, sdk) shouldBe DecoderRefusal.HeicTwelveBitNoFrame }
      }
      for (sdk in 24..27) withClue("API $sdk") { noFrameRefusal(coding, sdk) shouldBe DecoderRefusal.NoFrame }
    }
  }

  @Test
  fun aTwelveBitAvifOnApi36LandsAsAnEightBitOneAndReportsItsCut() {
    for ((matrix, full) in listOf(BT709 to false, YCGCO to true, BT601 to true)) {
      val input = twelveBit(matrix, full).forAndroidDecoder(36)
      val conversion = input.conversion.shouldNotBeNull()

      val eightBit = decoderConversionOf(reading(matrix), full, 36, avif = true, tenBit = false).shouldNotBeNull()
      listOf(conversion.matrix, conversion.fullRange) shouldBe listOf<Any?>(eightBit.matrix, eightBit.fullRange)
      withClue("matrix $matrix") { conversion.limits shouldBe emptyList() }
      decoderLimits(input.bytes, input, halfFloat = false) shouldBe listOf(DecoderLimit.AvifTwelveBitCut)
      decoderLimits(input.bytes, input, halfFloat = true) shouldBe emptyList()
    }
    val eightBit = ColorPictures.NCLX_BT709_LIMITED.bytes()
    decoderLimits(eightBit, eightBit.forAndroidDecoder(36), halfFloat = false) shouldBe emptyList()
  }

  @Test
  fun aTwelveBitAvifWhoseConversionNamesALimitReportsItAndTheCut() {
    for (full in listOf(true, false)) {
      val input = twelveBit(BT2020_CONSTANT, full).forAndroidDecoder(36)
      val readable = readableMatrices(full, 36, avif = true, tenBit = false)
      val nearest = reading(BT2020_CONSTANT).nearestReadable(readable).shouldNotBeNull()
      withClue("full $full") { (nearest.withinBound && !nearest.exact) shouldBe true }
      val landed = DecoderLimit.LandedThrough(avif = true, nearest)

      input.refusal.shouldBeNull()
      decoderLimits(input.bytes, input, halfFloat = false) shouldBe listOf(landed, DecoderLimit.AvifTwelveBitCut)
      decoderLimits(input.bytes, input, halfFloat = true) shouldBe listOf(landed)
    }
  }

  @Test
  fun aTwelveBitAvifLandingPastTheBoundOrCoreRefusesIsRefusedOnApi36() {
    val limitedYcgco = twelveBit(YCGCO, false).forAndroidDecoder(36)
    val readable = readableMatrices(false, 36, avif = true, tenBit = false)
    val nearest = reading(YCGCO).nearestReadable(readable).shouldNotBeNull()
    limitedYcgco.refusal shouldBe pastBound(avif = true, nearest)
    for (full in listOf(true, false)) {
      val ictcp = twelveBit(ICTCP, full)
      withClue("full $full") { ictcp.forAndroidDecoder(36).refusal shouldBe pictureYcbcrCoding(ictcp)?.refusal }
    }
  }

  // A moov box keeps core from rewriting the codestream, so the decoder reads the YCgCo it states through a matrix
  // filmstrip cannot name. The nearest it reads is already past the bound, so the picture is refused all the same.
  @Test
  fun aPictureCoreCannotRestateIsRefusedWhenTheNearestMatrixIsPastTheBound() {
    val withMoov = twelveBit(YCGCO, false) + box("moov", ByteArray(0))
    val coding = pictureYcbcrCoding(withMoov).shouldNotBeNull()
    val nearest = reading(YCGCO).nearestReadable(readableMatrices(false, 36, avif = true, false)).shouldNotBeNull()
    nearest.withinBound shouldBe false

    val input = withMoov.forAndroidDecoder(36)

    input.conversion.shouldBeNull()
    input.refusal shouldBe pastBound(avif = true, nearest)
    coding.unrestatedRefusal(36, handedFullRange = null) shouldBe pastBound(avif = true, nearest)
    val bt709 = twelveBit(BT709, false) + box("moov", ByteArray(0))
    bt709.forAndroidDecoder(36).refusal.shouldBeNull()
    pictureYcbcrCoding(bt709).shouldNotBeNull().unrestatedRefusal(36, handedFullRange = null).shouldBeNull()
  }

  @Test
  fun everyRestateStatesTheNearestMatrixBesideItsPairedPrimariesInTheNclxToo() {
    for ((avif, tenBit) in listOf(true to false, true to true, false to false)) {
      for (matrix in listOf(BT709, FCC, BT601, SMPTE_240, YCGCO, BT2020, BT2020_CONSTANT)) {
        for (sdk in listOf(31, 33, 34, 36)) {
          val readable = readableMatrices(true, sdk, avif, tenBit)
          val nearest = reading(matrix).nearestReadable(readable).shouldNotBeNull().matrix
          val want = RestatedColor(nearest, pairedPrimaries(nearest), nclx = true)

          withClue("matrix $matrix on $sdk") {
            restatedForDecoder(reading(matrix), true, sdk, avif, tenBit) shouldBe want
          }
        }
      }
    }
  }

  @Test
  fun aTwelveBitAvifNclxStatesTheRestatedMatrixOnApi36() {
    val prepared = twelveBit(BT2020_CONSTANT, false).forAndroidDecoder(36).bytes
    val coding = pictureYcbcrCoding(prepared).shouldNotBeNull()
    val readable = readableMatrices(false, 36, avif = true, tenBit = false)
    val nearest = reading(BT2020_CONSTANT).nearestReadable(readable).shouldNotBeNull().matrix

    coding.matrix shouldBe nearest
    coding.codestream shouldBe CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, false)
  }

  private fun coding(
    codec: CodecKind,
    depth: Int?,
  ): YcbcrCoding = YcbcrCoding(BT709, true, codec, codestream = null, bitDepth = depth, chromaFormat = 1)

  private fun twelveBit(
    matrix: Int,
    fullRange: Boolean,
  ): ByteArray = AvifColorPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, matrix, fullRange)).bytes()

  private companion object {
    const val UNSPECIFIED = 2
    const val BT709 = 1
    const val FCC = 4
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val ICTCP = 14
    const val SRGB_TRANSFER = 13
  }
}
