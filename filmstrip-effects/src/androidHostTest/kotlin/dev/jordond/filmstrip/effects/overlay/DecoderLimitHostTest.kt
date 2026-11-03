package dev.jordond.filmstrip.effects.overlay

import android.os.Build
import androidx.media3.common.Effect
import dev.jordond.filmstrip.color.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

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
      pictureYcbcrCoding(picture).shouldNotBeNull().identityNot444 shouldBe true

      val read = ImageSource.Bytes(picture).read(context = null)

      read.shouldBeInstanceOf<RefusedPicture>().reason shouldBe IDENTITY_NOT_444_REASON
    }
  }

  @Test
  fun aPictureCoreRefusesIsRefusedWithCoresReason() {
    for (picture in listOf(avifRecording(ICTCP, true), heicRecording(YDZDX, false))) {
      val refusal = pictureYcbcrCoding(picture)?.refusal.shouldNotBeNull()

      ImageSource
        .Bytes(picture)
        .read(context = null)
        .shouldBeInstanceOf<RefusedPicture>()
        .reason shouldBe refusal
    }
  }

  // YCgCo lands past the bound through every matrix Android's HEIC decoder reads, on every API level.
  @Test
  fun aPictureLandingPastTheBoundIsRefusedNamingCoresFigures() {
    for (full in listOf(true, false)) {
      val heic = heicRecording(YCGCO, full)
      val readable = readableMatrices(full, Build.VERSION.SDK_INT, avif = false, tenBit = false)
      val nearest = reading(YCGCO).nearestReadable(readable).shouldNotBeNull()
      nearest.withinBound shouldBe false

      val read = ImageSource.Bytes(heic).read(context = null).shouldBeInstanceOf<RefusedPicture>()

      read.reason shouldBe pastBound(avif = false, nearest)
      withClue(read.reason) { read.reason.endsWith("${nearest.figures}.") shouldBe true }
    }
  }

  @Test
  fun aSixteenBitPngDecodedIntoEightBitsIsCut() {
    val png = OverlayPixels.sixteenBitPngOf(SIDE, SIXTEEN_BIT_TEXEL)

    decoderLimits(png, png.forAndroidDecoder(LATEST_API), false) shouldBe listOf(DecoderLimit.EightBitCut)
  }

  @Test
  fun aSixteenBitPngDecodedIntoHalfFloatsKeepsItsDepth() {
    val png = OverlayPixels.sixteenBitPngOf(SIDE, SIXTEEN_BIT_TEXEL)

    decoderLimits(png, png.forAndroidDecoder(LATEST_API), halfFloat = true) shouldBe emptyList()
  }

  @Test
  fun noEightBitPictureIsCut() {
    val pictures =
      listOf(OverlayPixels.pngOf(SIDE, ColorPictures.TEXEL), ColorPictures.HEIC_NCLX_BT709_FULL.bytes()) +
        ColorPictures.AVIFS.map { it.bytes() } + listOf(ByteArray(0), OverlayPixels.pngOf(SIDE, 0).copyOf(PNG_HEADER))
    for (picture in pictures) limitsOf(picture, LATEST_API) shouldBe emptyList()
  }

  @Test
  fun aHeicStatingAMatrixItsDecoderCannotReadIsLimited() {
    limitsOf(heicRecording(SMPTE_240, true), 36) shouldBe listOf(landedThrough(SMPTE_240, true, LATEST_API))
    limitsOf(heicRecording(BT709, true), 30) shouldBe listOf(landedThrough(BT709, true, 30))
    limitsOf(heicRecording(BT709, false), 33) shouldBe listOf(landedThrough(BT709, false, 33))
    limitsOf(heicRecording(BT2020, true), 30) shouldBe listOf(landedThrough(BT2020, true, 30))
    limitsOf(heicRecording(BT709, false), 34) shouldBe emptyList()
  }

  @Test
  fun anAvifItsDecoderLandsShortOfItsFileIsLimited() {
    val bt709Limited = ColorPictures.NCLX_BT709_LIMITED.bytes()
    limitsOf(bt709Limited, 31) shouldBe listOf(landedThrough(BT709, false, 31, avif = true))
    limitsOf(bt709Limited, 33) shouldBe listOf(landedThrough(BT709, false, 33, avif = true))
    limitsOf(bt709Limited, 34) shouldBe emptyList()
    val tenBit34 = decoderConversionOf(reading(BT709), false, 34, avif = true, tenBit = true).shouldNotBeNull()
    tenBit34.limits shouldBe listOf(landedThrough(BT709, false, 34, avif = true, tenBit = true))
    decoderConversionOf(reading(BT709), false, 35, avif = true, tenBit = true)?.limits shouldBe emptyList()
    val smpte240 = avifRecording(SMPTE_240, true)
    limitsOf(smpte240, 35) shouldBe listOf(landedThrough(SMPTE_240, true, 35, avif = true))
    limitsOf(smpte240, LATEST_API) shouldBe emptyList()
    val bt709Full = avifRecording(BT709, true)
    val before = listOf(landedThrough(BT709, true, 31, avif = true), DecoderLimit.AvifBeforeApi33)
    limitsOf(bt709Full, 31) shouldBe before
    val undescribed = ColorPictures.PROFILE_DISPLAY_P3.bytes()
    val coding = pictureYcbcrCoding(undescribed).shouldNotBeNull()
    listOf(coding.fullRange, coding.reading == reading(BT601)) shouldBe listOf(true, true)
    for (sdk in listOf(31, 32)) {
      withClue("API $sdk") { limitsOf(undescribed, sdk) shouldBe listOf(DecoderLimit.AvifBeforeApi33) }
    }
    for (sdk in AVIF_API_LEVELS.filter { it >= 33 }) {
      withClue("API $sdk") {
        limitsOf(undescribed, sdk) shouldBe emptyList()
      }
    }
  }

  @Test
  fun aLimitedPictureResolvesAsItsLimitOnEveryGrade() {
    for (limit in limits()) {
      for (transfer in listOf(null) + HdrTransfer.entries) {
        val resolution =
          media3OverlayResolution(
            EFFECT,
            transfer,
            listOf(limit),
          ).shouldBeInstanceOf<EffectResolution.Degraded>()
        val expected = if (transfer == HdrTransfer.Hlg) listOf(limit.cause, HLG_SCENE_LIGHT) else listOf(limit.cause)

        withClue("$limit on $transfer") { resolution.causes shouldBe expected }
      }
    }
    val cuts = listOf(DecoderLimit.EightBitCut, DecoderLimit.AvifTwelveBitCut)
    for (limit in cuts) withClue("$limit") { limit.reason shouldBe DegradationReason.PrecisionReduced }
    for (limit in limits() - cuts.toSet()) {
      withClue("$limit") {
        limit.reason shouldBe DegradationReason.ColorSpaceConverted
      }
    }
    ("Android's AVIF decoder" in DecoderLimit.AvifBeforeApi33.message) shouldBe true
    ("API 31" in DecoderLimit.AvifBeforeApi33.message) shouldBe true
    for (avif in listOf(true, false)) {
      val limit = landedThrough(BT709, false, 33, avif)
      withClue(limit.message) { limit.message.startsWith(decoderName(avif)) shouldBe true }
      limit.message.endsWith("matrix ${limit.nearest.matrix} instead, ${limit.nearest.figures}.") shouldBe true
    }
  }

  @Test
  fun aPictureWithTwoLimitsReportsBothInOrder() {
    for (first in limits()) {
      for (second in limits() - first) {
        for (transfer in listOf(null) + HdrTransfer.entries) {
          val resolution = media3OverlayResolution(EFFECT, transfer, listOf(first, second))

          val causes = resolution.shouldBeInstanceOf<EffectResolution.Degraded>().causes.take(2)
          withClue("$first, $second on $transfer") { causes shouldBe listOf(first.cause, second.cause) }
        }
      }
    }
  }

  @Test
  fun aLimitedPictureOnAnHlgGradeKeepsTheSceneLightNote() {
    val limits = listOf(landedThrough(BT2020_CONSTANT, false, LATEST_API, avif = true), DecoderLimit.AvifTwelveBitCut)

    val resolution =
      media3OverlayResolution(
        EFFECT,
        HdrTransfer.Hlg,
        limits,
      ).shouldBeInstanceOf<EffectResolution.Degraded>()

    resolution.causes shouldBe limits.map { it.cause } + HLG_SCENE_LIGHT
    resolution.causes.map { it.reason } shouldBe
      listOf(
        DegradationReason.ColorSpaceConverted,
        DegradationReason.PrecisionReduced,
        DegradationReason.ApproximateAlgorithm,
      )
    withClue(resolution.causes.last().message) { ("scene light" in resolution.causes.last().message) shouldBe true }
  }

  @Test
  fun aRefusedPictureNamesWhatAndroidsDecoderRefuses() {
    ("API 33" in DecoderRefusal.AvifTenBitNoFrame.message) shouldBe true
    ("API 36" in DecoderRefusal.AvifTwelveBit.message) shouldBe true
    ("Android's HEIC decoder" in DecoderRefusal.HeicTwelveBitNoFrame.message) shouldBe true
    ("AVIF from API 31" in DecoderRefusal.NoFrame.message) shouldBe true
  }

  @Test
  fun aPictureThatKeepsItsDepthResolvesAsBefore() {
    media3OverlayResolution(EFFECT, null).shouldBeInstanceOf<EffectResolution.Resolved>()
    media3OverlayResolution(EFFECT, HdrTransfer.Pq).shouldBeInstanceOf<EffectResolution.Resolved>()
    val hlg = media3OverlayResolution(EFFECT, HdrTransfer.Hlg).shouldBeInstanceOf<EffectResolution.Degraded>()
    hlg.causes shouldBe listOf(HLG_SCENE_LIGHT)
    HLG_SCENE_LIGHT.reason shouldBe DegradationReason.ApproximateAlgorithm
    withClue(HLG_SCENE_LIGHT.message) { ("scene light" in HLG_SCENE_LIGHT.message) shouldBe true }
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
    val nearest = reading(matrix).nearestReadable(readable, fullRange, landedFullRange).shouldNotBeNull()
    withClue("matrix $matrix on API $sdk") { nearest.exact shouldBe false }
    return DecoderLimit.LandedThrough(avif, nearest)
  }

  private fun limitsOf(
    picture: ByteArray,
    sdk: Int,
  ): List<DecoderLimit> = decoderLimits(picture, picture.forAndroidDecoder(sdk), halfFloat = false)

  private companion object {
    val EFFECT = PlatformEffect(object : Effect {})
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
