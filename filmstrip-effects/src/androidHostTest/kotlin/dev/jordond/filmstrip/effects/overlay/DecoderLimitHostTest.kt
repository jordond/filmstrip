package dev.jordond.filmstrip.effects.overlay

import android.os.Build
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.OverlayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What an overlay picture reports once Android's decoder lands it short of its file, and how it resolves.
 *
 * A sixteen-bit picture decoded into eight bits, and an AVIF or HEIC that its decoder on its API level lands
 * through another matrix or range, resolve as degraded on every grade, naming each limit. A picture core refuses, and
 * one whose nearest matrix lands past the bound, is refused before the decoder sees it, and any other picture resolves
 * as before, degraded only for media3's HLG blend.
 */
class DecoderLimitHostTest {
  @Test
  fun anIdentityPictureOnSubsampledSamplesIsRefusedBeforeItsDecoderSeesIt() {
    for (picture in listOf(avifRecording(0, true), heicRecording(0, true), avifRecording(0, false))) {
      assertTrue(assertNotNull(pictureYcbcrCoding(picture)).identityNot444)

      val read = ImageSource.Bytes(picture).read(context = null)

      assertEquals(IDENTITY_NOT_444_REASON, assertIs<RefusedPicture>(read).reason)
    }
  }

  @Test
  fun aPictureCoreRefusesIsRefusedWithCoresReason() {
    for (picture in listOf(avifRecording(ICTCP, true), heicRecording(YDZDX, false))) {
      val refusal = assertNotNull(pictureYcbcrCoding(picture)?.refusal)

      assertEquals(refusal, assertIs<RefusedPicture>(ImageSource.Bytes(picture).read(context = null)).reason)
    }
  }

  // YCgCo lands past the bound through every matrix Android's HEIC decoder reads, on every API level.
  @Test
  fun aPictureLandingPastTheBoundIsRefusedNamingCoresFigures() {
    for (full in listOf(true, false)) {
      val heic = heicRecording(YCGCO, full)
      val readable = readableMatrices(full, Build.VERSION.SDK_INT, avif = false, tenBit = false)
      val nearest = assertNotNull(reading(YCGCO).nearestReadable(readable))
      assertFalse(nearest.withinBound)

      val read = assertIs<RefusedPicture>(ImageSource.Bytes(heic).read(context = null))

      assertEquals(pastBound(avif = false, nearest), read.reason)
      assertTrue(read.reason.endsWith("${nearest.figures}."), read.reason)
    }
  }

  @Test
  fun aSixteenBitPngDecodedIntoEightBitsIsCut() {
    val png = OverlayPixels.sixteenBitPngOf(SIDE, SIXTEEN_BIT_TEXEL)

    assertEquals(listOf(DecoderLimit.EightBitCut), decoderLimits(png, png.forAndroidDecoder(LATEST_API), false))
  }

  @Test
  fun aSixteenBitPngDecodedIntoHalfFloatsKeepsItsDepth() {
    val png = OverlayPixels.sixteenBitPngOf(SIDE, SIXTEEN_BIT_TEXEL)

    assertEquals(emptyList(), decoderLimits(png, png.forAndroidDecoder(LATEST_API), halfFloat = true))
  }

  @Test
  fun noEightBitPictureIsCut() {
    val pictures =
      listOf(OverlayPixels.pngOf(SIDE, ColourPictures.TEXEL), ColourPictures.HEIC_NCLX_BT709_FULL.bytes()) +
        ColourPictures.AVIFS.map { it.bytes() } + listOf(ByteArray(0), OverlayPixels.pngOf(SIDE, 0).copyOf(PNG_HEADER))
    for (picture in pictures) assertEquals(emptyList(), limitsOf(picture, LATEST_API))
  }

  @Test
  fun aHeicStatingAMatrixItsDecoderCannotReadIsLimited() {
    assertEquals(listOf(landedThrough(SMPTE_240, true, LATEST_API)), limitsOf(heicRecording(SMPTE_240, true), 36))
    assertEquals(listOf(landedThrough(BT709, true, 30)), limitsOf(heicRecording(BT709, true), 30))
    assertEquals(listOf(landedThrough(BT709, false, 33)), limitsOf(heicRecording(BT709, false), 33))
    assertEquals(listOf(landedThrough(BT2020, true, 30)), limitsOf(heicRecording(BT2020, true), 30))
    assertEquals(emptyList(), limitsOf(heicRecording(BT709, false), 34))
  }

  @Test
  fun anAvifItsDecoderLandsShortOfItsFileIsLimited() {
    val bt709Limited = ColourPictures.NCLX_BT709_LIMITED.bytes()
    assertEquals(listOf(landedThrough(BT709, false, 31, avif = true)), limitsOf(bt709Limited, 31))
    assertEquals(listOf(landedThrough(BT709, false, 33, avif = true)), limitsOf(bt709Limited, 33))
    assertEquals(emptyList(), limitsOf(bt709Limited, 34))
    val tenBit34 = assertNotNull(decoderLanding(reading(BT709), false, 34, avif = true, tenBit = true))
    assertEquals(listOf(landedThrough(BT709, false, 34, avif = true, tenBit = true)), tenBit34.limits)
    assertEquals(emptyList(), decoderLanding(reading(BT709), false, 35, avif = true, tenBit = true)?.limits)
    val smpte240 = avifRecording(SMPTE_240, true)
    assertEquals(listOf(landedThrough(SMPTE_240, true, 35, avif = true)), limitsOf(smpte240, 35))
    assertEquals(emptyList(), limitsOf(smpte240, LATEST_API))
    val bt709Full = avifRecording(BT709, true)
    val before = listOf(landedThrough(BT709, true, 31, avif = true), DecoderLimit.AvifBeforeApi33)
    assertEquals(before, limitsOf(bt709Full, 31))
    val undescribed = ColourPictures.PROFILE_DISPLAY_P3.bytes()
    val coding = assertNotNull(pictureYcbcrCoding(undescribed))
    assertEquals(listOf(true, true), listOf(coding.fullRange, coding.reading == reading(BT601)))
    for (sdk in listOf(31, 32)) {
      assertEquals(listOf(DecoderLimit.AvifBeforeApi33), limitsOf(undescribed, sdk), "API $sdk")
    }
    for (sdk in AVIF_API_LEVELS.filter { it >= 33 }) assertEquals(emptyList(), limitsOf(undescribed, sdk), "API $sdk")
  }

  @Test
  fun aLimitedPictureResolvesAsItsLimitOnEveryGrade() {
    for (limit in limits()) {
      for (transfer in listOf(null) + HdrTransfer.entries) {
        val resolution = assertIs<EffectResolution.Degraded>(media3OverlayResolution(EFFECT, transfer, listOf(limit)))
        val expected = if (transfer == HdrTransfer.Hlg) listOf(limit.cause, HLG_SCENE_LIGHT) else listOf(limit.cause)

        assertEquals(expected, resolution.causes, "$limit on $transfer")
      }
    }
    val cuts = listOf(DecoderLimit.EightBitCut, DecoderLimit.AvifTwelveBitCut)
    for (limit in cuts) assertEquals(DegradationReason.PrecisionReduced, limit.reason, "$limit")
    for (limit in limits() - cuts.toSet()) assertEquals(DegradationReason.ColorSpaceConverted, limit.reason, "$limit")
    assertTrue("Android's AVIF decoder" in DecoderLimit.AvifBeforeApi33.message)
    assertTrue("API 31" in DecoderLimit.AvifBeforeApi33.message)
    for (avif in listOf(true, false)) {
      val limit = landedThrough(BT709, false, 33, avif)
      assertTrue(limit.message.startsWith(decoderName(avif)), limit.message)
      assertTrue(limit.message.endsWith("matrix ${limit.nearest.matrix} instead, ${limit.nearest.figures}."))
    }
  }

  @Test
  fun aPictureWithTwoLimitsReportsBothInOrder() {
    for (first in limits()) {
      for (second in limits() - first) {
        for (transfer in listOf(null) + HdrTransfer.entries) {
          val resolution = media3OverlayResolution(EFFECT, transfer, listOf(first, second))

          val causes = assertIs<EffectResolution.Degraded>(resolution).causes.take(2)
          assertEquals(listOf(first.cause, second.cause), causes, "$first, $second on $transfer")
        }
      }
    }
  }

  @Test
  fun aLimitedPictureOnAnHlgGradeKeepsTheSceneLightNote() {
    val limits = listOf(landedThrough(BT2020_CONSTANT, false, LATEST_API, avif = true), DecoderLimit.AvifTwelveBitCut)

    val resolution = assertIs<EffectResolution.Degraded>(media3OverlayResolution(EFFECT, HdrTransfer.Hlg, limits))

    assertEquals(limits.map { it.cause } + HLG_SCENE_LIGHT, resolution.causes)
    assertEquals(
      listOf(
        DegradationReason.ColorSpaceConverted,
        DegradationReason.PrecisionReduced,
        DegradationReason.ApproximateAlgorithm,
      ),
      resolution.causes.map { it.reason },
    )
    assertTrue("scene light" in resolution.causes.last().message, resolution.causes.last().message)
  }

  @Test
  fun aRefusedPictureNamesWhatAndroidsDecoderRefuses() {
    assertTrue("API 33" in DecoderRefusal.AvifTenBitNoFrame.message)
    assertTrue("API 36" in DecoderRefusal.AvifTwelveBit.message)
    assertTrue("Android's HEIC decoder" in DecoderRefusal.HeicTwelveBitNoFrame.message)
    assertTrue("AVIF from API 31" in DecoderRefusal.NoFrame.message)
  }

  @Test
  fun aPictureThatKeepsItsDepthResolvesAsBefore() {
    assertIs<EffectResolution.Resolved>(media3OverlayResolution(EFFECT, null))
    assertIs<EffectResolution.Resolved>(media3OverlayResolution(EFFECT, HdrTransfer.Pq))
    val hlg = assertIs<EffectResolution.Degraded>(media3OverlayResolution(EFFECT, HdrTransfer.Hlg))
    assertEquals(listOf(HLG_SCENE_LIGHT), hlg.causes)
    assertEquals(DegradationReason.ApproximateAlgorithm, HLG_SCENE_LIGHT.reason)
    assertTrue("scene light" in HLG_SCENE_LIGHT.message, HLG_SCENE_LIGHT.message)
  }

  // Every kind of limit, a landing on each decoder among them.
  private fun limits(): List<DecoderLimit> =
    listOf(
      DecoderLimit.EightBitCut,
      DecoderLimit.AvifTwelveBitCut,
      DecoderLimit.AvifBeforeApi33,
      landedThrough(BT709, false, 33, avif = true),
      landedThrough(BT709, false, 33, avif = false),
    )

  // The limit a picture recording [matrix] at [fullRange] reports when its decoder on [sdk] lands it through the
  // nearest matrix core finds among the ones it reads, at limited range for an AVIF before API 33.
  private fun landedThrough(
    matrix: Int,
    fullRange: Boolean,
    sdk: Int,
    avif: Boolean = false,
    tenBit: Boolean = false,
  ): DecoderLimit.LandedThrough {
    val readable = readableMatrices(fullRange, sdk, avif, tenBit)
    val landedFullRange = fullRange && !(avif && sdk < 33)
    val nearest = assertNotNull(reading(matrix).nearestReadable(readable, fullRange, landedFullRange))
    assertFalse(nearest.exact, "matrix $matrix on API $sdk")
    return DecoderLimit.LandedThrough(avif, nearest)
  }

  private fun limitsOf(
    picture: ByteArray,
    sdk: Int,
  ): List<DecoderLimit> = decoderLimits(picture, picture.forAndroidDecoder(sdk), halfFloat = false)

  private companion object {
    val EFFECT = PlatformEffect(Any())
    val SIDE = Size(4, 4)
    val SIXTEEN_BIT_TEXEL = listOf(0x8000, 0x4000, 0xC000, 0xFFFF)
    const val BT709 = 1
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val YDZDX = 11
    const val ICTCP = 14
    const val LATEST_API = 36

    // Every level Android decodes AVIF on, 32 given API 31's landing.
    val AVIF_API_LEVELS = listOf(31, 32, 33, 34, 35, LATEST_API)

    // The signature and IHDR up to its height, one byte short of the bit depth.
    const val PNG_HEADER = 24
  }
}
