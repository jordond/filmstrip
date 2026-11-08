package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.overlay.BitmapLayouts.Layout
import dev.jordond.filmstrip.effects.overlay.BitmapLayouts.bytes
import dev.jordond.filmstrip.effects.overlay.BitmapLayouts.premultiplied
import dev.jordond.filmstrip.effects.unreadSamples
import dev.jordond.filmstrip.effects.unreadableImage
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.picturePeakBytes
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceCMYK
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetGrayFillColor
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.CoreGraphics.kCGBitmapFloatComponents
import platform.CoreGraphics.kCGColorSpaceExtendedLinearDisplayP3
import platform.CoreGraphics.kCGColorSpaceGenericGrayGamma2_2
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the samples ImageIO decodes a picture to as the ones the Apple overlay raster reads back, whatever layout they
 * are stored in.
 *
 * Every layout [bitmapCodes] takes reads back at the straight color its bytes hold, read one after another in one
 * process so a grey layout read before an RGB one cannot leave its layout behind. A translucent HEIC reads back at the
 * color ImageIO decodes it to, its color above its alpha included, and so does a translucent text plate. A texel
 * with no alpha reads back black, and one Core Image hands back as not a number reads back as 0. A grey HEIC with
 * alpha, whose samples ImageIO does not hand out, is refused saying so, and a CMYK image is refused too. A grey layout
 * the read does not take is refused naming its alpha and byte order, and bytes holding no picture are refused naming
 * the formats ImageIO and Core Image decode.
 */
@OptIn(ExperimentalForeignApi::class)
class ImageIoBitmapHardwareTest {
  @Test
  fun `every layout reads back at the straight color it stores`() {
    assertRasterReads()
    val misses =
      BitmapLayouts.ALL.mapNotNull { layout ->
        val codes = layout.image().useImage { it.bitmapCodes() }.orFail(layout.name)
        val read = OverlayPixels(codes).centre() ?: return@mapNotNull "${layout.name} read no samples"
        val expected = layout.straight.map { (it * MAX_SAMPLE).roundToInt() }
        if (read == expected) null else "${layout.name} read $read, where it stores $expected"
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // A grey copy holds ImageIO's decode, the copy read from it and the RGBA written from that, and an RGB copy holds the
  // decode beside the copy that becomes the image.
  @Test
  fun `a copy whose peak passes the ceiling is refused before its samples are copied out`() {
    val misses =
      BitmapLayouts.ALL.mapNotNull { layout ->
        layout.image().useImage { image ->
          val width = CGImageGetWidth(image).toLong()
          val height = CGImageGetHeight(image).toLong()
          val decode = CGImageGetBytesPerRow(image).toLong() * height
          val rgba = width * height * CHANNELS * (CGImageGetBitsPerComponent(image).toLong() / BITS)
          val peak =
            if (layout.grey) picturePeakBytes(decode, decode, rgba) else picturePeakBytes(decode, 0L, decode)
          val ceiling = withClue(layout.name) { peak.shouldNotBeNull() }
          val past = image.bitmapCodes(ceiling = ceiling - 1)
          val at = image.bitmapCodes(ceiling = ceiling)
          when {
            (past as? PictureRead.Refused)?.reason != tooLargeToCopy(width, height, ceiling - 1) -> {
              "${layout.name} was not refused just past its peak"
            }
            at !is PictureRead.Read -> {
              "${layout.name} was refused at its peak"
            }
            else -> {
              null
            }
          }
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `a cmyk image is refused`() {
    val layout = Layout("CMYK8", grey = false, BITS, CGImageAlphaInfo.kCGImageAlphaNone.value, bytes(10, 20, 30, 40))
    val read = layout.image(CGColorSpaceCreateDeviceCMYK()).useImage { it.bitmapCodes() }

    withClue("a CMYK image was read") { read.shouldBeInstanceOf<PictureRead.Refused>() }
  }

  @Test
  fun `a grey image with alpha first is refused naming its layout`() {
    val layout =
      Layout("GreyA8 first", grey = true, BITS, CGImageAlphaInfo.kCGImageAlphaFirst.value, bytes(128, 200))
    val read =
      withClue("alpha first was read") {
        layout.image().useImage { it.bitmapCodes() }.shouldBeInstanceOf<PictureRead.Refused>()
      }

    read.reason shouldBe
      "ImageIO decoded it to 8-bit integer grey with kCGImageAlphaFirst in kCGBitmapByteOrderDefault. Grey is read " +
      "at eight or sixteen bits, as integers, with no alpha or alpha last."
  }

  // The float source decodes to premultiplied eight-bit samples whose straight red sits above their alpha, and the
  // eight-bit source decodes to straight ones.
  @Test
  fun `a translucent heic reads back at the color imageio decodes it to`() {
    assertRasterReads()
    val misses =
      listOf(Painted.Float, Painted.EightBit).mapNotNull { painted ->
        val heic = heicOf(painted)
        val expected = imageIoStraight(heic)
        val read =
          OverlayPixels(
            ImageSource
              .ofBytes(heic)
              .read()
              .orFail("the $painted HEIC")
              .held,
          ).centre()
        when {
          read == null -> "the $painted HEIC read no samples"
          read.indices.any {
            abs(
              read[it] - expected[it],
            ) > 1
          } -> "the $painted HEIC read $read, ImageIO gives $expected"
          else -> null
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun `the float heic holds red above its alpha`() {
    val heic = heicOf(Painted.Float)
    val expected = imageIoStraight(heic)

    withClue("ImageIO decoded the HEIC to $expected, so no color sits above alpha") {
      (expected[0] > expected[ALPHA]) shouldBe true
    }
  }

  @Test
  fun `a translucent text plate reads back at its straight color`() {
    assertRasterReads()
    val style = TextStyle(color = OPAQUE_BLACK, backgroundColor = HALF_WHITE)
    val drawn = rasterizeText("H", style, FRAME)
    val raster =
      withClue("the text drew nothing") {
        drawn.shouldBeInstanceOf<TextRaster.Drawn<OverlayPixels>>()
      }.pixels
    val stored = withClue("the plate read nothing") { raster.stored.shouldNotBeNull() }
    val corner = stored.take(CHANNELS).map { it.toInt() and SAMPLE_MASK }

    withClue("the plate's corner lost its color") { corner shouldBe listOf(MAX, MAX, MAX, HALF * EIGHT_BIT_STEP) }
  }

  @Test
  fun `a grey heic with alpha is refused saying imageio handed out no samples`() {
    val heic = heicOf(Painted.Grey)
    val read =
      withClue("ImageIO hands out a grey HEIC's samples") {
        ImageSource.ofBytes(heic).read().shouldBeInstanceOf<PictureRead.Refused>()
      }
    read.reason shouldBe SAMPLES_WITHHELD

    val overlay = ImageOverlay(image = ImageSource.ofBytes(heic), corner = Corner.TopStart)
    val resolved = BuiltInEffectResolver().resolve(overlay, CAPABILITIES, ATTRIBUTES)

    val refused =
      withClue("the grey HEIC resolved as $resolved") {
        resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe unreadSamples(SAMPLES_WITHHELD)
  }

  @Test
  fun `bytes holding no picture are refused naming the formats apple decodes`() {
    val overlay = ImageOverlay(image = ImageSource.ofBytes(bytes(1, 2, 3)), corner = Corner.TopStart)
    val resolved = BuiltInEffectResolver().resolve(overlay, CAPABILITIES, ATTRIBUTES)

    val refused =
      withClue("three stray bytes resolved as $resolved") {
        resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe unreadableImage(overlay.image)
    listOf("ImageIO or Core Image", "PNG", "JPEG", "HEIC", "AVIF", "WebP", "JPEG XL").forEach { named ->
      refused.message shouldContain named
    }
  }

  private fun OverlayPixels.centre(): List<Int>? {
    val samples = stored ?: return null
    val offset = (size.height / 2 * size.width + size.width / 2) * CHANNELS
    return List(CHANNELS) { samples[offset + it].toInt() and SAMPLE_MASK }
  }

  private inline fun <T> CGImageRef.useImage(read: (CGImageRef) -> T): T =
    try {
      read(this)
    } finally {
      CGImageRelease(this)
    }

  /**
   * The straight sixteen-bit color ImageIO decodes [heic] to at its centre, read from the eight or sixteen-bit RGBA
   * bitmap it hands back and divided by alpha here when that bitmap is premultiplied.
   */
  private fun imageIoStraight(heic: ByteArray): List<Int> {
    val data = heic.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), heic.size.toLong()) }
    val source = CGImageSourceCreateWithData(data, null).shouldNotBeNull()
    CFRelease(data)
    val image = CGImageSourceCreateImageAtIndex(source, 0u, null).shouldNotBeNull()
    CFRelease(source)
    return image.useImage {
      val info = CGImageGetBitmapInfo(it)
      val alpha = info and kCGBitmapAlphaInfoMask
      val depth = CGImageGetBitsPerComponent(it).toInt()
      val order = info and kCGBitmapByteOrderMask
      withClue("ImageIO decoded the HEIC to a layout this read does not take, bitmap info $info at $depth bits") {
        (
          alpha in RGBA_LAST && CGImageGetBitsPerPixel(it).toInt() == depth * CHANNELS &&
            ((depth == BITS && order == 0u) || (depth == 2 * BITS && order == kCGBitmapByteOrder16Little))
        ) shouldBe true
      }
      val samples = CGDataProviderCopyData(CGImageGetDataProvider(it)).shouldNotBeNull()
      val pointer = CFDataGetBytePtr(samples).shouldNotBeNull()
      val bytes = depth / BITS
      val centre =
        (CGImageGetHeight(it).toInt() / 2 * CGImageGetBytesPerRow(it).toInt()) +
          CGImageGetWidth(it).toInt() / 2 * CHANNELS * bytes
      val texel =
        List(CHANNELS) { channel ->
          (0 until bytes).sumOf { byte ->
            (pointer[centre + channel * bytes + byte].toInt() and BYTE) shl (BITS * byte)
          }
        }
      CFRelease(samples)
      val full = (1 shl depth) - 1.0
      val premultipliedTexel = alpha == CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
      List(CHANNELS) { channel ->
        val straight =
          when {
            channel == ALPHA -> texel[ALPHA] / full
            premultipliedTexel -> premultiplied(texel[channel], texel[ALPHA])
            else -> texel[channel] / full
          }
        (straight * MAX_SAMPLE).roundToInt()
      }
    }
  }

  /**
   * How a HEIC is painted at half alpha before ImageIO writes it.
   */
  private enum class Painted { Float, EightBit, Grey }

  /**
   * A HEIC ImageIO writes from a context painted at half alpha the way [painted] says, failing the calling test when
   * ImageIO writes none.
   */
  private fun heicOf(painted: Painted): ByteArray {
    val space: CGColorSpaceRef? =
      when (painted) {
        Painted.Float -> CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearDisplayP3)
        Painted.EightBit -> CGColorSpaceCreateDeviceRGB()
        Painted.Grey -> CGColorSpaceCreateWithName(kCGColorSpaceGenericGrayGamma2_2)
      }
    val float =
      CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapFloatComponents or
        kCGBitmapByteOrder16Little
    val depth = if (painted == Painted.Float) 16uL else 8uL
    val info = if (painted == Painted.Float) float else CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
    val context =
      withClue("no $painted context") {
        CGBitmapContextCreate(null, 16u, 16u, depth, 0u, space, info).shouldNotBeNull()
      }
    CGColorSpaceRelease(space)
    if (painted == Painted.Grey) {
      CGContextSetGrayFillColor(context, 0.6, 0.5)
    } else {
      CGContextSetRGBFillColor(context, 0.9, 0.4, 0.2, 0.5)
    }
    CGContextFillRect(context, CGRectMake(0.0, 0.0, 16.0, 16.0))
    val image = CGBitmapContextCreateImage(context).shouldNotBeNull()
    CGContextRelease(context)

    val data = CFDataCreateMutable(null, 0).shouldNotBeNull()
    val type = CFStringCreateWithCString(null, HEIC, kCFStringEncodingUTF8)
    val destination = CGImageDestinationCreateWithData(data, type, 1u, null)
    type?.let(::CFRelease)
    val written =
      destination?.let {
        CGImageDestinationAddImage(it, image, null)
        CGImageDestinationFinalize(it).also { _ -> CFRelease(it) }
      } ?: false
    CGImageRelease(image)
    val bytes = if (written) CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt()) else null
    CFRelease(data)
    return withClue("ImageIO wrote no $HEIC for the $painted picture") { bytes.shouldNotBeNull() }
  }

  private companion object {
    const val BITS = 8
    const val CHANNELS = 4
    const val ALPHA = 3
    const val MAX = 65535
    const val MAX_SAMPLE = 65535.0
    const val HALF = 128
    const val OPAQUE_BLACK = 0xFF000000.toInt()
    const val HALF_WHITE = 0x80FFFFFF.toInt()
    val FRAME = Size(64, 32)

    const val HEIC = "public.heic"
    const val BYTE = 0xFF

    val RGBA_LAST =
      listOf(
        CGImageAlphaInfo.kCGImageAlphaLast,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast,
      ).map { it.value }

    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(0.seconds, 1.seconds),
      )

    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = true,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 8_192,
        features = emptySet(),
      )
  }
}
