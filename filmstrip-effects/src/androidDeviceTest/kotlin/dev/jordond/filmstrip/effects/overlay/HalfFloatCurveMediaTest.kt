package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.UNKNOWN_HALF_FLOAT_SPACE
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Half float bitmaps read back by [rasterFor] through the curve [halfFloatCurve] reads off their color space.
 *
 * A sixteen-bit picture decoded into extended sRGB and the same picture decoded into linear extended sRGB raster onto
 * the codes [pictureSrgbCodes] gives the file's samples, on an SDR frame and a PQ one alike, and neither reports a
 * limit. A ten-bit AVIF decodes into half floats that hold the ten bits its YCbCr samples give it, landing where
 * [decoderConversionOf] says for a ten-bit picture, or refused where core refuses it or it lands past the bound, except
 * on API 33, whose AVIF decoder hands back no frame for it at all, so the overlay is refused naming that limit. A half
 * float bitmap in any other space is refused rather than read, by the raster and by the resolver.
 */
class HalfFloatCurveMediaTest {
  @Before
  fun halfFloatBitmapsExist() {
    assumeTrue("Half float bitmaps arrive in API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
  }

  @Test
  fun aSixteenBitPictureDecodesIntoExtendedSrgbAndRastersOntoItsSamples() {
    val bitmap =
      ImageSource
        .Bytes(PICTURE)
        .decode(context = null)
        .shouldNotBeNull()
        .bitmap

    bitmap.halfFloatCurve() shouldBe HalfFloatCurve.Srgb
    assertSdrCodes(bitmap)
  }

  @Test
  fun aSixteenBitPictureDecodedIntoHalfFloatsKeepsItsDepth() {
    ImageSource
      .Bytes(PICTURE)
      .decode(context = null)
      .shouldNotBeNull()
      .limits shouldBe emptyList()
  }

  @Test
  fun aTenBitAvifDecodesIntoHalfFloatsThatHoldItsTenBits() {
    assumeTenBitAvifDecodes()

    val picture = ImageSource.Bytes(TEN_BIT_AVIF).decode(context = null).shouldNotBeNull()
    picture.bitmap.halfFloatCurve() shouldBe HalfFloatCurve.Srgb
    picture.limits shouldBe emptyList()
    val codes = picture.bitmap.centreCodes()
    val want =
      TEN_BIT_YCBCR.let { (y, cb, cr) ->
        ycbcrToRgb(y, cb, cr, BT709, BT709_PRIMARIES, SRGB_TRANSFER, fullRange = true, bitDepth = 10)
      }
    val off = codes.indices.maxOf { abs(codes[it] - want[it]) }
    withClue("decoded $codes where its YCbCr gives ${want.toList()}") { (off <= ONE_CODE) shouldBe true }
    val between = codes.maxOf { abs(it - it.roundToInt()) }
    withClue("decoded $codes, every channel on an eight-bit code") { (between >= BETWEEN_CODES) shouldBe true }
  }

  @Test
  fun aTenBitAvifAtEachMatrixAndRangeLandsWhereItsDecoderLandsIt() {
    assumeTenBitAvifDecodes()
    val misses =
      listOf(BT709, FCC, BT601, SMPTE_240, YCGCO, BT2020, BT2020_CONSTANT, ICTCP).flatMap { matrix ->
        listOf(true, false).mapNotNull { full ->
          val file = tenBitAvif(CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, matrix, full))
          tenBitMiss("matrix $matrix full range $full", file, file.ownConversion())
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // Android's own AVIF decoder on API 34, pinned so a platform that starts reading the matrix shows here.
  @Test
  fun onApi34ATenBitBt709AvifAtLimitedRangeLandsThroughTheNearestMatrixAndSaysSo() {
    assumeTenBitAvifDecodes()
    assumeTrue("This test pins API 34's AVIF decoder", Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val limited = tenBitAvif(CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, BT709, false))
    val readable = readableMatrices(false, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, avif = true, tenBit = true)
    val nearest =
      pictureYcbcrCoding(limited)
        .shouldNotBeNull()
        .reading
        .nearestReadable(readable)
        .shouldNotBeNull()
    val conversion = DecoderConversion(nearest.matrix, false, listOf(DecoderLimit.LandedThrough(avif = true, nearest)))

    (BT709 !in readable && nearest.withinBound) shouldBe true
    tenBitMiss("BT.709 limited", limited, conversion).shouldBeNull()
  }

  // Android's own AVIF decoder on API 33, pinned so a platform that starts handing back a frame shows here.
  @Test
  fun onApi33ATenBitAvifHandsBackNoFrameAndItsOverlayIsRefusedByName() {
    assumeTrue("This test pins API 33's AVIF decoder", Build.VERSION.SDK_INT == TEN_BIT_API)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(TEN_BIT_AVIF, 0, TEN_BIT_AVIF.size, bounds)
    bounds.outConfig shouldBe RGBA_1010102
    withClue(TEN_BIT_AVIF_UNDECODED) {
      BitmapFactory.decodeByteArray(TEN_BIT_AVIF, 0, TEN_BIT_AVIF.size).shouldBeNull()
    }

    val picture = ImageSource.Bytes(TEN_BIT_AVIF).read(context = null).shouldBeInstanceOf<RefusedPicture>()
    picture.reason shouldBe DecoderRefusal.AvifTenBitNoFrame.message
    val overlay = ImageOverlay(ImageSource.Bytes(TEN_BIT_AVIF), Corner.TopStart, 0f, OVERLAY_SCALE)
    val resolution = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, ATTRIBUTES, ANY_SIDE) }
    resolution.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe
      DecoderRefusal.AvifTenBitNoFrame.message
  }

  @Test
  fun anOverlayDecodedIntoAnyOtherHalfFloatSpaceIsRefused() {
    val displayP3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    val p3 = Bitmap.createBitmap(SIDE.width, SIDE.height, Bitmap.Config.RGBA_F16, true, displayP3)
    assumeTrue("This Android makes a half float bitmap in ${p3.colorSpace}", p3.colorSpace == displayP3)
    val overlay = ImageOverlay(ImageSource.Bytes(PICTURE), Corner.TopStart, 0f, OVERLAY_SCALE)
    val picture = DecodedPicture(p3, color = null)

    val resolution = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, ATTRIBUTES, ANY_SIDE) }

    resolution.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldBe UNKNOWN_HALF_FLOAT_SPACE
  }

  @Test
  fun aSixteenBitPictureDecodedIntoLinearLightRastersOntoItsSamples() {
    val options =
      BitmapFactory.Options().apply {
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.RGBA_F16
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
      }
    val linear = BitmapFactory.decodeByteArray(PICTURE, 0, PICTURE.size, options).shouldNotBeNull()
    assumeTrue("This Android decodes into ${linear.colorSpace} when asked for linear", linear.isLinear())

    linear.halfFloatCurve() shouldBe HalfFloatCurve.Linear
    assertSdrCodes(linear)
    val extended =
      ImageSource
        .Bytes(PICTURE)
        .decode(context = null)
        .shouldNotBeNull()
        .bitmap
    val want = extended.rasterFor(HdrTransfer.Pq)
    val read = linear.rasterFor(HdrTransfer.Pq)
    read.hdrLuminance shouldBe want.hdrLuminance
    val off = channelsOff(read.bitmap.getPixel(1, 1), want.bitmap.getPixel(1, 1))
    withClue("the linear decode lands $off codes off the extended sRGB one on PQ") {
      (off <= HALF_FLOAT_CODES) shouldBe true
    }
  }

  @Test
  fun aHalfFloatBitmapInAnyOtherSpaceIsRefused() {
    val displayP3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    val p3 = Bitmap.createBitmap(SIDE.width, SIDE.height, Bitmap.Config.RGBA_F16, true, displayP3)
    assumeTrue("This Android makes a half float bitmap in ${p3.colorSpace}", p3.colorSpace == displayP3)

    p3.halfFloatCurve() shouldBe HalfFloatCurve.Unknown
    assertFailsWith<IllegalStateException> { p3.rasterFor(transfer = null) }
  }

  // A half float carries the sample within a sixteenth of an eight-bit code, and the linear one goes through the sRGB
  // curve and back, so either lands within a code of core's read of the file.
  private fun assertSdrCodes(bitmap: Bitmap) {
    val want = pictureSrgbCodes(null, ShortArray(TEXEL.size) { TEXEL[it].toShort() })[0]
    val read = bitmap.rasterFor(transfer = null).bitmap.getPixel(1, 1)
    val off = channelsOff(read, want)
    withClue("${bitmap.colorSpace} rastered ${read.hex()} where core gives ${want.hex()}") {
      (off <= HALF_FLOAT_CODES) shouldBe true
    }
  }

  private fun assumeTenBitAvifDecodes() {
    assumeTrue("Android's AVIF decoder hands back a ten-bit frame from API 34", Build.VERSION.SDK_INT > TEN_BIT_API)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(TEN_BIT_AVIF, 0, TEN_BIT_AVIF.size, bounds)
    assumeTrue("This Android decodes a ten-bit AVIF to ${bounds.outConfig}", bounds.outConfig == RGBA_1010102)
  }

  // How [file] read through read misses landing where [conversion] says at ten bits, with the limits it names, or null.
  // One core refuses, which has no conversion, or one landing past the bound misses unless it is refused for it.
  private fun tenBitMiss(
    what: String,
    file: ByteArray,
    conversion: DecoderConversion?,
  ): String? {
    if (conversion.refused) return file.refusalMiss(what, conversion)
    checkNotNull(conversion)
    val picture = ImageSource.Bytes(file).read(context = null)
    if (picture !is DecodedPicture) return "$what came to $picture"
    if (picture.limits != conversion.limits) return "$what reported ${picture.limits}, not ${conversion.limits}"
    val codes = picture.bitmap.centreCodes()
    val (y, cb, cr) = TEN_BIT_YCBCR
    val coding = checkNotNull(pictureYcbcrCoding(file)) { "$what has no YCbCr coding" }
    val want =
      ycbcrToRgb(y, cb, cr, conversion.matrix, coding.primaries, coding.transfer, conversion.fullRange, bitDepth = 10)
    val off = codes.indices.maxOf { abs(codes[it] - want[it]) }
    return if (off <= ONE_CODE) null else "$what decoded $codes where its YCbCr gives ${want.toList()}"
  }

  private fun Bitmap.centreCodes(): List<Float> {
    val read = getColor(width / 2, height / 2)
    return (0 until RGB).map { read.getComponent(it) * MAX_CODE }
  }

  private fun Bitmap.isLinear(): Boolean = colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)

  private companion object {
    val SIDE = Size(4, 4)

    // A mid-range texel, so the linear and sRGB curves sit well apart on every channel.
    val TEXEL = listOf(0x6000, 0x9A00, 0x3400, 0xFFFF)
    val PICTURE = OverlayPixels.sixteenBitPngOf(SIDE, TEXEL)
    const val HALF_FLOAT_CODES = 1
    const val RGB = 3
    const val BT709 = 1
    const val BT709_PRIMARIES = 1
    const val SRGB_TRANSFER = 13
    const val BT601 = 6
    const val BT2020 = 9
    const val FCC = 4
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020_CONSTANT = 10
    const val ICTCP = 14
    const val MAX_CODE = 255f

    // ycbcrToRgb rounds to eight-bit codes, so a read that keeps ten bits sits within a code of them.
    const val ONE_CODE = 1f

    // The fixture's channels sit 0.36, 0.06 and 0.44 of a code past an eight-bit code, so one cut to eight bits sits
    // under this on every channel.
    const val BETWEEN_CODES = 0.25f
    val RGBA_1010102: Bitmap.Config? = if (Build.VERSION.SDK_INT >= TEN_BIT_API) Bitmap.Config.RGBA_1010102 else null
    val TEN_BIT_YCBCR = listOf(526, 368, 733)
    const val TEN_BIT_API = Build.VERSION_CODES.TIRAMISU
    const val TEN_BIT_AVIF_UNDECODED = "Android's AVIF decoder on API 33 hands back no frame for a ten-bit AVIF"
    const val OVERLAY_SCALE = 0.2f

    // A texture side no picture here reaches, so only how it was decoded refuses it.
    const val ANY_SIDE = Int.MAX_VALUE
    val FRAME = Size(64, 64)
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = dev.jordond.filmstrip.media.ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )

    // A flat AV1 still at ten bits, from ffmpeg 9.0.2 with SVT-AV1 4.2.0 on macOS. ten.yuv holds one 64 by 64 frame of
    // little endian sixteen-bit samples, 4096 of luma 514, then 1024 of blue difference 386 and 1024 of red difference
    // 706:
    //   ffmpeg -f rawvideo -pix_fmt yuv420p10le -s 64x64 -i ten.yuv -frames:v 1 -color_range pc -c:v libsvtav1 \
    //     -crf 1 -preset 8 -svtav1-params still-picture=1 -f avif ten.avif
    // The codestream is the file's mdat payload and the configuration its av1C payload. Decoding it back with ffmpeg
    // gives 526, 368 and 733 at every sample.
    const val TEN_BIT_AV1 = "0a0b02000005557ffc6af9c8403218100081020000000000800003402d101c97ffca72f6c242a8"
    const val TEN_BIT_AV1_CONFIGURATION = "81004c00"
    val TEN_BIT_AVIF = tenBitAvif(CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, BT709, true))

    // The ten-bit still with [nclx] as its color box. Its codestream states no color and full range.
    fun tenBitAvif(nclx: CicpTag): ByteArray =
      HeifStills.still(
        brand = "avif",
        itemType = "av01",
        configuration = box("av1C", TEN_BIT_AV1_CONFIGURATION.hex()),
        codestream = TEN_BIT_AV1.hex(),
        size = FRAME,
        nclx = nclx,
        bitDepth = 10,
      )

    private fun box(
      type: String,
      payload: ByteArray,
    ): ByteArray {
      val size = 8 + payload.size
      val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
      return header + type.encodeToByteArray() + payload
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
