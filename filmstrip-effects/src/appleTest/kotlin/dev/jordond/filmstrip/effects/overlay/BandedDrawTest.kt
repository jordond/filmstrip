package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.rasterReader
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.pictureColorFromDecodedProfile
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCopyICCData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGColorSpaceDisplayP3
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Holds a described picture drawn a band of rows at a time to the floats its whole frame of samples gives when they
 * are read and converted at once, at eight and sixteen bits, with alpha and without, on an SDR frame and on each HDR
 * transfer. It also holds a picture too large to draw at its color to a refusal on each frame that converts it, and
 * an untagged one to drawing at its codes on an SDR frame, which converts nothing.
 *
 * The draws are read back through the raster's own [codeReader].
 */
@OptIn(ExperimentalForeignApi::class)
class BandedDrawTest {
  @Test
  fun `a picture drawn in bands lands every texel where its whole frame converted at once does`() {
    val reader = rasterReader()
    val color = displayP3()

    val misses =
      Layout.entries.flatMap { layout ->
        val image = layout.picture()
        val codes = image.bitmapCodes().orFail(layout.name)
        CGImageRelease(image)
        val whole = codes.image.storedSamples(SIZE, reader, bandTexels = WIDTH * HEIGHT)
        val banded = OverlayPixels(codes, readColor = { color }, bandTexels = BAND_TEXELS_HERE)
        val sdr =
          miss(
            "${layout.name} on SDR",
            banded.enteringFrame(transfer = null, ColorSpace.Bt709),
            wholeSrgb(color, whole, codes.image).inCodesOf(ColorSpace.Bt709),
            reader,
          )
        val hdr =
          HdrTransfer.entries.map { transfer ->
            miss(
              "${layout.name} on ${transfer.name}",
              banded.enteringFrame(transfer, ColorSpace.Bt2020),
              wholeLight(color, whole, transfer),
              reader,
            )
          }
        listOfNotNull(sdr) + hdr.filterNotNull()
      }

    misses shouldBe emptyList()
  }

  @Test
  fun `grey rows of an eight-bit display p3 picture come out at their own codes`() {
    // The SDR draw makes its floats at the first band that leaves its codes, here the third, and reads the two grey
    // bands above it again, so the case above crosses that restart as well as the band edges.
    val grey = ShortArray(GREY_ROWS * WIDTH * CHANNELS) { index -> sampleOf(Layout.EIGHT_OPAQUE, index) }
    val first = GREY_ROWS * WIDTH * CHANNELS
    val colored = ShortArray(WIDTH * CHANNELS) { index -> sampleOf(Layout.EIGHT_OPAQUE, first + index) }

    (pictureSrgbCodes(displayP3(), grey) contentEquals pictureSrgbCodes(null, grey)) shouldBe true
    !(pictureSrgbCodes(displayP3(), colored) contentEquals pictureSrgbCodes(null, colored)) shouldBe true
  }

  @Test
  fun `an untagged picture past the ceiling draws on an sdr frame and is refused on every hdr one`() {
    val reader = rasterReader()
    val image = Layout.EIGHT_STRAIGHT.picture()
    val codes = image.bitmapCodes().orFail("the picture")
    CGImageRelease(image)
    val peak = drawnPeakBytes(WIDTH.toLong(), HEIGHT.toLong(), codes.bytes, BAND_TEXELS_HERE).shouldNotBeNull()
    val past = OverlayPixels(codes, bandTexels = BAND_TEXELS_HERE, ceiling = peak - 1)
    val at = OverlayPixels(codes, bandTexels = BAND_TEXELS_HERE, ceiling = peak)

    val sdr = past.enteringFrame(transfer = null, ColorSpace.Bt709)
    val refusals =
      HdrTransfer.entries.map { transfer ->
        assertFailsWith<PictureTooLargeToDraw>("a picture past the ceiling was drawn on ${transfer.name}") {
          past.enteringFrame(transfer, ColorSpace.Bt2020)
        }.message
      }

    miss("the untagged picture on SDR", sdr, codes.image.inCodesOf(ColorSpace.Bt709), reader).shouldBeNull()
    refusals shouldBe HdrTransfer.entries.map { tooLargeToDraw(WIDTH.toLong(), HEIGHT.toLong(), peak - 1) }
    HdrTransfer.entries.forEach { at.enteringFrame(it, ColorSpace.Bt2020) }
  }

  @Test
  fun `codes whose held bytes cannot be counted are refused as too large to draw instead of counted as none`() {
    assertRasterReads()
    val image = Layout.EIGHT_STRAIGHT.picture()
    val codes = image.bitmapCodes().orFail("the picture")
    CGImageRelease(image)
    val uncounted = OverlayPixels(HeldCodes(codes.image, bytes = null), bandTexels = BAND_TEXELS_HERE)

    drawnPeakBytes(WIDTH.toLong(), HEIGHT.toLong(), held = null, BAND_TEXELS_HERE).shouldBeNull()
    HdrTransfer.entries.forEach { transfer ->
      val refused =
        assertFailsWith<PictureTooLargeToDraw>("uncounted codes were drawn on ${transfer.name}") {
          uncounted.enteringFrame(transfer, ColorSpace.Bt2020)
        }
      refused.message shouldBe tooLargeToDraw(WIDTH.toLong(), HEIGHT.toLong(), PICTURE_PEAK_CEILING)
    }
  }

  @Test
  fun `a described picture past the ceiling is refused on an sdr frame that converts it every time it is drawn`() {
    assertRasterReads()
    val image = Layout.EIGHT_OPAQUE.picture()
    val codes = image.bitmapCodes().orFail("the picture")
    CGImageRelease(image)
    val peak = drawnPeakBytes(WIDTH.toLong(), HEIGHT.toLong(), codes.bytes, BAND_TEXELS_HERE).shouldNotBeNull()
    val color = displayP3()
    val past = OverlayPixels(codes, readColor = { color }, bandTexels = BAND_TEXELS_HERE, ceiling = peak - 1)
    val at = OverlayPixels(codes, readColor = { color }, bandTexels = BAND_TEXELS_HERE, ceiling = peak)

    val refusals =
      List(2) {
        assertFailsWith<PictureTooLargeToDraw>("a picture past the ceiling was drawn on SDR") {
          past.enteringFrame(transfer = null, ColorSpace.Bt709)
        }.message
      }

    refusals shouldBe List(2) { tooLargeToDraw(WIDTH.toLong(), HEIGHT.toLong(), peak - 1) }
    at.enteringFrame(transfer = null, ColorSpace.Bt709)
  }

  @Test
  fun `the drawn peak counts the codes and floats beside one band and only an eight-bit 48 mp picture fits`() {
    val floatsAndBand = 8_000L * 6_000 * 16 + 131L * 8_000 * 48
    val eightBit = drawnPeakBytes(8_000, 6_000, held = 8_000L * 6_000 * 4).shouldNotBeNull()
    val sixteenBit = drawnPeakBytes(8_000, 6_000, held = 8_000L * 6_000 * 8).shouldNotBeNull()

    floatsAndBand shouldBe 818_304_000L
    eightBit shouldBe picturePeakBytes(8_000L * 6_000 * 4, 131L * 8_000 * 48, 8_000L * 6_000 * 16)
    eightBit shouldBe 1_010_304_000L
    (eightBit <= PICTURE_PEAK_CEILING) shouldBe true
    sixteenBit shouldBe 1_202_304_000L
    (sixteenBit > PICTURE_PEAK_CEILING) shouldBe true
    drawnPeakBytes(WIDTH.toLong(), HEIGHT.toLong(), held = 7) shouldBe WIDTH * HEIGHT * (16L + 48) + 7
    drawnPeakBytes(4_000, 3_000, held = 0, bandTexels = 8_000) shouldBe 4_000L * 3_000 * 16 + 4_000L * 2 * 48
    drawnPeakBytes(0, 6_000, held = 9) shouldBe 9L
    drawnPeakBytes(Int.MAX_VALUE.toLong(), 1, held = 0).shouldBeNull()
    drawnPeakBytes(1, Long.MAX_VALUE, held = 0).shouldBeNull()
    drawnPeakBytes(1, 1, held = Long.MAX_VALUE).shouldBeNull()
  }

  @Test
  fun `floats that cannot be allocated are refused as too large to draw`() {
    val width = 1 shl 30
    val height = 1 shl 28
    val refused =
      assertFailsWith<PictureTooLargeToDraw>("floats past what the process can allocate were made") {
        PremultipliedFloats(Size(width, height), PICTURE_PEAK_CEILING)
      }

    refused.message shouldBe tooLargeToDraw(width.toLong(), height.toLong(), PICTURE_PEAK_CEILING)
  }

  /**
   * What was drawn read back beside what was expected, as a sentence naming the first texel that differs, or null when
   * every float matches.
   */
  private fun miss(
    name: String,
    drawn: CIImage,
    expected: CIImage,
    reader: CIContext,
  ): String? {
    val read = floatsOf(drawn, reader)
    val wanted = floatsOf(expected, reader)
    val index = read.indices.firstOrNull { read[it] != wanted[it] } ?: return null
    val texel = index / CHANNELS
    return "$name drew ${read[index]} at channel ${index % CHANNELS} of texel ${texel % WIDTH}, ${texel / WIDTH} " +
      "where the whole frame gives ${wanted[index]}"
  }

  private fun floatsOf(
    image: CIImage,
    reader: CIContext,
  ): FloatArray {
    val floats = FloatArray(WIDTH * HEIGHT * CHANNELS)
    floats.usePinned {
      reader.render(
        image = image,
        toBitmap = it.addressOf(0),
        rowBytes = (WIDTH * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(0.0, 0.0, WIDTH.toDouble(), HEIGHT.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = null,
      )
    }
    return floats
  }

  // The SDR image the whole frame gives, made the way the raster made it before it drew in bands.
  private fun wholeSrgb(
    color: PictureColor,
    samples: ShortArray,
    codes: CIImage,
  ): CIImage {
    val drawn = pictureSrgbCodes(color, samples)
    val eightBit = samples.all { (it.toInt() and 0xFFFF) % 257 == 0 }
    if (eightBit && drawn contentEquals pictureSrgbCodes(null, samples)) return codes
    val floats = FloatArray(drawn.size * CHANNELS)
    drawn.forEachIndexed { index, color ->
      val alpha = (color ushr 24) / MAX_CODE
      floats[index * CHANNELS] = ((color shr 16) and 0xFF) / MAX_CODE * alpha
      floats[index * CHANNELS + 1] = ((color shr 8) and 0xFF) / MAX_CODE * alpha
      floats[index * CHANNELS + 2] = (color and 0xFF) / MAX_CODE * alpha
      floats[index * CHANNELS + 3] = alpha
    }
    return floats.asImage(null)
  }

  // The HDR image the whole frame gives on transfer, each texel's signal premultiplied by its alpha.
  private fun wholeLight(
    color: PictureColor,
    samples: ShortArray,
    transfer: HdrTransfer,
  ): CIImage {
    val texels = pictureLight(color, samples)
    for (offset in texels.indices step CHANNELS) {
      val signal = transfer.signalFromNits(texels.copyOfRange(offset, offset + 3))
      repeat(3) { texels[offset + it] = signal[it] * texels[offset + 3] }
    }
    val name = if (transfer == HdrTransfer.Pq) kCGColorSpaceITUR_2100_PQ else kCGColorSpaceITUR_2100_HLG
    val space = CGColorSpaceCreateWithName(name)
    try {
      return texels.asImage(space)
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  @OptIn(BetaInteropApi::class)
  private fun FloatArray.asImage(space: CGColorSpaceRef?): CIImage {
    val data = usePinned { NSData.create(bytes = it.addressOf(0), length = (size * Float.SIZE_BYTES).toULong()) }
    return CIImage.imageWithBitmapData(
      data,
      (WIDTH * CHANNELS * Float.SIZE_BYTES).toULong(),
      CGSizeMake(WIDTH.toDouble(), HEIGHT.toDouble()),
      kCIFormatRGBAf,
      space,
    )
  }

  // Tagged with a frame's code space the way an SDR frame tags the image it draws.
  private fun CIImage.inCodesOf(colorSpace: ColorSpace): CIImage {
    val space = colorSpace.createCodeSpace()
    try {
      return imageByColorMatchingColorSpaceToWorkingSpace(space) ?: this
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  private fun displayP3(): PictureColor {
    val space = CGColorSpaceCreateWithName(kCGColorSpaceDisplayP3)
    val icc = CFBridgingRelease(CGColorSpaceCopyICCData(space)) as NSData
    CGColorSpaceRelease(space)
    val profile = ByteArray(icc.length.toInt())
    profile.usePinned { memcpy(it.addressOf(0), icc.bytes, icc.length) }
    return withClue("Display P3 read as no description") {
      pictureColorFromDecodedProfile(profile, 3).shouldNotBeNull()
    }
  }

  /**
   * The ways the picture is stored, eight or sixteen bits a sample, with straight alpha or none.
   */
  private enum class Layout(
    val wide: Boolean,
    val alpha: Boolean,
  ) {
    EIGHT_STRAIGHT(wide = false, alpha = true),
    EIGHT_OPAQUE(wide = false, alpha = false),
    SIXTEEN_STRAIGHT(wide = true, alpha = true),
    SIXTEEN_OPAQUE(wide = true, alpha = false),
    ;

    fun picture(): CGImageRef {
      val bytesPerSample = if (wide) 2 else 1
      val bytes = ByteArray(WIDTH * HEIGHT * CHANNELS * bytesPerSample)
      for (index in 0 until WIDTH * HEIGHT * CHANNELS) {
        val sample = sampleOf(this, index).toInt() and 0xFFFF
        if (wide) {
          bytes[index * 2] = sample.toByte()
          bytes[index * 2 + 1] = (sample shr 8).toByte()
        } else {
          bytes[index] = (sample / 257).toByte()
        }
      }
      val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
      val provider = CGDataProviderCreateWithCFData(data)
      CFRelease(data)
      val space = CGColorSpaceCreateDeviceRGB()
      val alphaInfo = if (alpha) CGImageAlphaInfo.kCGImageAlphaLast else CGImageAlphaInfo.kCGImageAlphaNoneSkipLast
      val depth = 8 * bytesPerSample
      val image =
        CGImageCreate(
          WIDTH.toULong(),
          HEIGHT.toULong(),
          depth.toULong(),
          (CHANNELS * depth).toULong(),
          (WIDTH * CHANNELS * bytesPerSample).toULong(),
          space,
          if (wide) kCGBitmapByteOrder16Little or alphaInfo.value else alphaInfo.value,
          provider,
          null,
          false,
          CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
      CGColorSpaceRelease(space)
      CGDataProviderRelease(provider)
      return withClue("Core Graphics made no $name picture") { image.shouldNotBeNull() }
    }
  }

  private companion object {
    const val WIDTH = 7
    const val HEIGHT = 9
    const val CHANNELS = 4
    const val GREY_ROWS = 4
    const val MAX_CODE = 255f

    // Two rows a band, so nine rows draw in four full bands and one of a single row.
    const val BAND_TEXELS_HERE = 2 * WIDTH
    val SIZE = Size(WIDTH, HEIGHT)

    /**
     * The sixteen-bit sample [layout] stores at [index], counting red, green, blue and alpha along each row from the
     * top. The first [GREY_ROWS] rows are grey and every texel below differs. An eight-bit layout stores codes times
     * 257 and a picture with no alpha stores it opaque. With alpha, every fifth texel has none.
     */
    fun sampleOf(
      layout: Layout,
      index: Int,
    ): Short {
      val texel = index / CHANNELS
      val x = texel % WIDTH
      val y = texel / WIDTH
      val channel = index % CHANNELS
      val code =
        when {
          channel == 3 && !layout.alpha -> 255
          channel == 3 -> if (texel % 5 == 4) 0 else 255 - 19 * y - 3 * x
          y < GREY_ROWS -> 20 + 33 * x + 7 * y
          else -> listOf(10 + 35 * x, 5 + 27 * y, 3 + 11 * (x + y))[channel]
        }
      val fine = if (layout.wide && channel != 3) (x * 97 + y * 31 + channel * 13) % 257 else 0
      return (code * 257 - fine).coerceIn(0, 65535).toShort()
    }
  }
}
