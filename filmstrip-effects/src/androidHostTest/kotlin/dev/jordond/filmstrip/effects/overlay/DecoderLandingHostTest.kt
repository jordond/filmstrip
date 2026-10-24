package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicColourPictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a picture's depth and codec decide what Android's decoder is handed and what it reports.
 *
 * Only a ten-bit picture takes the ten-bit answers. A twelve-bit AVIF is refused by name from API 31 to 35 and lands
 * as an eight-bit one from API 36, reporting any limit its landing names and then the cut to eight bits, or refused
 * when its landing is past the bound. A twelve-bit HEIC the decoder hands back no frame for is named from API 28,
 * where Android first decodes HEIC. Every restate states the nearest matrix in the `nclx` too.
 */
class DecoderLandingHostTest {
  @Test
  fun onlyTenBitSamplesTakeTheTenBitAnswers() {
    assertFalse(coding(CodecKind.Av1, depth = 8).tenBitSamples)
    assertTrue(coding(CodecKind.Av1, depth = 10).tenBitSamples)
    assertFalse(coding(CodecKind.Av1, depth = 12).tenBitSamples)
    assertFalse(coding(CodecKind.Av1, depth = null).tenBitSamples)
    assertTrue(coding(CodecKind.Hevc, depth = 10).tenBitSamples)
  }

  @Test
  fun onlyAnAv1PictureIsAnAvifAndOnlyATenBitOneIsATenBitAvif() {
    assertTrue(coding(CodecKind.Av1, depth = 8).avif)
    assertFalse(coding(CodecKind.Hevc, depth = 8).avif)
    assertTrue(coding(CodecKind.Av1, depth = 10).tenBitAvif)
    for (depth in listOf(8, 12, null)) assertFalse(coding(CodecKind.Av1, depth).tenBitAvif, "depth $depth")
    assertFalse(coding(CodecKind.Hevc, depth = 10).tenBitAvif)
  }

  @Test
  fun onlyATwelveBitHevcPictureIsATwelveBitHeic() {
    assertTrue(coding(CodecKind.Hevc, depth = 12).twelveBitHeic)
    for (depth in listOf(8, 10, null)) assertFalse(coding(CodecKind.Hevc, depth).twelveBitHeic, "depth $depth")
    assertFalse(coding(CodecKind.Av1, depth = 12).twelveBitHeic)
    val fixture = HeicColourPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, BT709, true))
    assertTrue(assertNotNull(pictureYcbcrCoding(fixture)).twelveBitHeic)
  }

  @Test
  fun onlyATwelveBitAv1PictureIsATwelveBitAvif() {
    assertFalse(coding(CodecKind.Av1, depth = 8).twelveBitAvif)
    assertFalse(coding(CodecKind.Av1, depth = 10).twelveBitAvif)
    assertTrue(coding(CodecKind.Av1, depth = 12).twelveBitAvif)
    assertFalse(coding(CodecKind.Av1, depth = null).twelveBitAvif)
    assertFalse(coding(CodecKind.Hevc, depth = 12).twelveBitAvif)
    val fixture = assertNotNull(pictureYcbcrCoding(twelveBit(BT709, true)))
    assertTrue(fixture.twelveBitAvif)
    assertFalse(fixture.tenBitSamples)
  }

  @Test
  fun aTwelveBitAvifIsRefusedByNameBelowApi36() {
    for (full in listOf(true, false)) {
      val coding = pictureYcbcrCoding(twelveBit(BT709, full))
      for (sdk in listOf(31, 32, 33, 34, 35)) {
        assertEquals(DecoderRefusal.AvifTwelveBit.message, refusalBeforeDecoder(coding, sdk), "API $sdk")
      }
      for (sdk in listOf(24, 30, 36, 37)) assertNull(refusalBeforeDecoder(coding, sdk), "API $sdk")
      assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(coding, 30))
    }
  }

  @Test
  fun onlyATwelveBitAvifOrAnIdentityPictureOnSubsampledSamplesIsRefusedBeforeTheDecoder() {
    for (depth in listOf(8, 10, null)) assertNull(refusalBeforeDecoder(coding(CodecKind.Av1, depth), 33), "$depth")
    for (sdk in listOf(28, 33, 36)) assertNull(refusalBeforeDecoder(coding(CodecKind.Hevc, depth = 12), sdk), "$sdk")
    assertNull(refusalBeforeDecoder(null, 33))
    val identity = YcbcrCoding(0, true, CodecKind.Av1, codestream = null, bitDepth = 12, chromaFormat = 1)
    assertEquals(IDENTITY_NOT_444_REASON, refusalBeforeDecoder(identity, 36))
    for (matrix in listOf(3, 11, 14, 15)) {
      val refused = YcbcrCoding(matrix, true, CodecKind.Hevc, codestream = null, bitDepth = 8, chromaFormat = 1)
      assertEquals(assertNotNull(refused.refusal), refusalBeforeDecoder(refused, 33), "matrix $matrix")
    }
  }

  @Test
  fun onlyATenBitAvifOnApi33NamesTheFrameItsDecoderHandsBackNone() {
    val tenBit = coding(CodecKind.Av1, depth = 10)
    assertEquals(DecoderRefusal.AvifTenBitNoFrame, noFrameRefusal(tenBit, 33))
    for (sdk in listOf(31, 32, 34, 35, 36)) {
      assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(tenBit, sdk), "API $sdk")
    }
    for (depth in listOf(8, 12, null)) {
      assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(coding(CodecKind.Av1, depth), 33), "depth $depth")
    }
    assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(coding(CodecKind.Hevc, depth = 10), 33))
    assertEquals(DecoderRefusal.HeicTwelveBitNoFrame, noFrameRefusal(coding(CodecKind.Hevc, depth = 12), 33))
    assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(null, 33))
  }

  @Test
  fun aTwelveBitHeicIsNamedFromApi28AndIsAPlainNoFrameBelow() {
    for (full in listOf(true, false)) {
      val coding = pictureYcbcrCoding(HeicColourPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, BT709, full)))
      for (sdk in listOf(28, 29, 30, 31, 32, 33, 34, 35, 36)) {
        assertEquals(DecoderRefusal.HeicTwelveBitNoFrame, noFrameRefusal(coding, sdk), "API $sdk")
      }
      for (sdk in 24..27) assertEquals(DecoderRefusal.NoFrame, noFrameRefusal(coding, sdk), "API $sdk")
    }
  }

  @Test
  fun aTwelveBitAvifOnApi36LandsAsAnEightBitOneAndReportsItsCut() {
    for ((matrix, full) in listOf(BT709 to false, YCGCO to true, BT601 to true)) {
      val input = twelveBit(matrix, full).forAndroidDecoder(36)
      val landing = assertNotNull(input.landing)

      val eightBit = assertNotNull(decoderLanding(reading(matrix), full, 36, avif = true, tenBit = false))
      assertEquals(listOf<Any?>(eightBit.matrix, eightBit.fullRange), listOf(landing.matrix, landing.fullRange))
      assertEquals(emptyList(), landing.limits, "matrix $matrix")
      assertEquals(listOf(DecoderLimit.AvifTwelveBitCut), decoderLimits(input.bytes, input, halfFloat = false))
      assertEquals(emptyList(), decoderLimits(input.bytes, input, halfFloat = true))
    }
    val eightBit = ColourPictures.NCLX_BT709_LIMITED.bytes()
    assertEquals(emptyList(), decoderLimits(eightBit, eightBit.forAndroidDecoder(36), halfFloat = false))
  }

  @Test
  fun aTwelveBitAvifWhoseLandingNamesALimitReportsItAndTheCut() {
    for (full in listOf(true, false)) {
      val input = twelveBit(BT2020_CONSTANT, full).forAndroidDecoder(36)
      val readable = readableMatrices(full, 36, avif = true, tenBit = false)
      val nearest = assertNotNull(reading(BT2020_CONSTANT).nearestReadable(readable))
      assertTrue(nearest.withinBound && !nearest.exact, "full $full")
      val landed = DecoderLimit.LandedThrough(avif = true, nearest)

      assertNull(input.refusal)
      assertEquals(listOf(landed, DecoderLimit.AvifTwelveBitCut), decoderLimits(input.bytes, input, halfFloat = false))
      assertEquals(listOf(landed), decoderLimits(input.bytes, input, halfFloat = true))
    }
  }

  @Test
  fun aTwelveBitAvifLandingPastTheBoundOrCoreRefusesIsRefusedOnApi36() {
    val limitedYcgco = twelveBit(YCGCO, false).forAndroidDecoder(36)
    val readable = readableMatrices(false, 36, avif = true, tenBit = false)
    val nearest = assertNotNull(reading(YCGCO).nearestReadable(readable))
    assertEquals(pastBound(avif = true, nearest), limitedYcgco.refusal)
    for (full in listOf(true, false)) {
      val ictcp = twelveBit(ICTCP, full)
      assertEquals(pictureYcbcrCoding(ictcp)?.refusal, ictcp.forAndroidDecoder(36).refusal, "full $full")
    }
  }

  // A moov box keeps core from rewriting the codestream, so the decoder reads the YCgCo it states through a matrix
  // filmstrip cannot name. The nearest it reads is already past the bound, so the picture is refused all the same.
  @Test
  fun aPictureCoreCannotRestateIsRefusedWhenTheNearestMatrixIsPastTheBound() {
    val withMoov = twelveBit(YCGCO, false) + box("moov", ByteArray(0))
    val coding = assertNotNull(pictureYcbcrCoding(withMoov))
    val nearest = assertNotNull(reading(YCGCO).nearestReadable(readableMatrices(false, 36, avif = true, false)))
    assertFalse(nearest.withinBound)

    val input = withMoov.forAndroidDecoder(36)

    assertNull(input.landing)
    assertEquals(pastBound(avif = true, nearest), input.refusal)
    assertEquals(pastBound(avif = true, nearest), coding.unrestatedRefusal(36, handedFullRange = null))
    val bt709 = twelveBit(BT709, false) + box("moov", ByteArray(0))
    assertNull(bt709.forAndroidDecoder(36).refusal)
    assertNull(assertNotNull(pictureYcbcrCoding(bt709)).unrestatedRefusal(36, handedFullRange = null))
  }

  @Test
  fun everyRestateStatesTheNearestMatrixBesideItsPairedPrimariesInTheNclxToo() {
    for ((avif, tenBit) in listOf(true to false, true to true, false to false)) {
      for (matrix in listOf(BT709, FCC, BT601, SMPTE_240, YCGCO, BT2020, BT2020_CONSTANT)) {
        for (sdk in listOf(31, 33, 34, 36)) {
          val readable = readableMatrices(true, sdk, avif, tenBit)
          val nearest = assertNotNull(reading(matrix).nearestReadable(readable)).matrix
          val want = RestatedColour(nearest, pairedPrimaries(nearest), nclx = true)

          assertEquals(want, restatedForDecoder(reading(matrix), true, sdk, avif, tenBit), "matrix $matrix on $sdk")
        }
      }
    }
  }

  @Test
  fun aTwelveBitAvifNclxStatesTheRestatedMatrixOnApi36() {
    val prepared = twelveBit(BT2020_CONSTANT, false).forAndroidDecoder(36).bytes
    val coding = assertNotNull(pictureYcbcrCoding(prepared))
    val readable = readableMatrices(false, 36, avif = true, tenBit = false)
    val nearest = assertNotNull(reading(BT2020_CONSTANT).nearestReadable(readable)).matrix

    assertEquals(nearest, coding.matrix)
    assertEquals(CodestreamSignal(pairedPrimaries(nearest), UNSPECIFIED, nearest, false), coding.codestream)
  }

  private fun coding(
    codec: CodecKind,
    depth: Int?,
  ): YcbcrCoding = YcbcrCoding(BT709, true, codec, codestream = null, bitDepth = depth, chromaFormat = 1)

  private fun twelveBit(
    matrix: Int,
    fullRange: Boolean,
  ): ByteArray = AvifColourPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, matrix, fullRange)).bytes()

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
