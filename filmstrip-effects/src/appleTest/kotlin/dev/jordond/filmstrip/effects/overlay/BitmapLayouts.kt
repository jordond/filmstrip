package dev.jordond.filmstrip.effects.overlay

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGBitmapFloatComponents

/**
 * The stored layouts the overlay raster's bitmap route is checked against, each a texel repeated over a small image
 * with the straight color it holds.
 *
 * Tests import these by name.
 */
@OptIn(ExperimentalForeignApi::class)
internal object BitmapLayouts {
  /**
   * One stored layout, [texel] repeated over a four by two image, and the straight color it holds from 0 to 1.
   */
  class Layout(
    val name: String,
    val grey: Boolean,
    val depth: Int,
    val info: UInt,
    val texel: ByteArray,
    val straight: List<Double> = emptyList(),
  ) {
    /**
     * The image this layout stores, its samples read in [colors], or in device grey or RGB when that is null.
     */
    fun image(colors: CGColorSpaceRef? = null): CGImageRef {
      val bytes = ByteArray(texel.size * WIDTH * HEIGHT) { texel[it % texel.size] }
      val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
      val provider = CGDataProviderCreateWithCFData(data)
      CFRelease(data)
      val space = colors ?: if (grey) CGColorSpaceCreateDeviceGray() else CGColorSpaceCreateDeviceRGB()
      val image =
        CGImageCreate(
          WIDTH.toULong(),
          HEIGHT.toULong(),
          depth.toULong(),
          (texel.size * BITS).toULong(),
          (texel.size * WIDTH).toULong(),
          space,
          info,
          provider,
          null,
          false,
          CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
      CGColorSpaceRelease(space)
      CGDataProviderRelease(provider)
      return withClue("Core Graphics made no $name image") { image.shouldNotBeNull() }
    }
  }

  private const val WIDTH = 4
  private const val HEIGHT = 2
  private const val BITS = 8
  private const val CHANNELS = 4
  private const val MAX_SAMPLE = 65535.0

  fun premultiplied(
    color: Int,
    alpha: Int,
  ): Double = color.toDouble() / alpha

  fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

  private fun eight(code: Int): Double = code / 255.0

  private fun little16(vararg values: Int): ByteArray =
    values.flatMap { listOf(it.toByte(), (it shr BITS).toByte()) }.toByteArray()

  private fun little32(vararg values: Float): ByteArray =
    values
      .flatMap { value ->
        value.toRawBits().let { bits -> List(4) { (bits shr (BITS * it)).toByte() } }
      }.toByteArray()

  private val TRANSPARENT = List(CHANNELS) { 0.0 }
  private val STRAIGHT_LAST = CGImageAlphaInfo.kCGImageAlphaLast.value
  private val STRAIGHT_LAST_16 = STRAIGHT_LAST or kCGBitmapByteOrder16Little
  private val PREMULTIPLIED_FLOATS =
    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Little or kCGBitmapFloatComponents

  /**
   * Every layout the bitmap route reads, grey ones first, so an RGB layout read after one would show a grey layout
   * left behind.
   */
  val ALL =
    listOf(
      Layout("L8", true, 8, CGImageAlphaInfo.kCGImageAlphaNone.value, bytes(153), List(3) { eight(153) } + 1.0),
      Layout(
        "LA8 straight",
        true,
        8,
        CGImageAlphaInfo.kCGImageAlphaLast.value,
        bytes(153, 128),
        List(3) { eight(153) } + eight(128),
      ),
      Layout(
        "L16 little endian",
        true,
        16,
        CGImageAlphaInfo.kCGImageAlphaNone.value or kCGBitmapByteOrder16Little,
        little16(40000),
        List(3) { 40000 / MAX_SAMPLE } + 1.0,
      ),
      Layout("LA8 transparent", true, 8, STRAIGHT_LAST, bytes(153, 0), TRANSPARENT),
      Layout(
        "RGBA8 premultiplied",
        false,
        8,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        bytes(77, 51, 26, 128),
        listOf(premultiplied(77, 128), premultiplied(51, 128), premultiplied(26, 128), eight(128)),
      ),
      Layout(
        "RGBA8 straight",
        false,
        8,
        CGImageAlphaInfo.kCGImageAlphaLast.value,
        bytes(153, 102, 51, 128),
        listOf(eight(153), eight(102), eight(51), eight(128)),
      ),
      Layout("RGBA8 straight transparent", false, 8, STRAIGHT_LAST, bytes(153, 102, 51, 0), TRANSPARENT),
      Layout(
        "RGBX8",
        false,
        8,
        CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        bytes(153, 102, 51, 7),
        listOf(eight(153), eight(102), eight(51), 1.0),
      ),
      Layout(
        "BGRA8 premultiplied",
        false,
        8,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
        bytes(26, 51, 77, 128),
        listOf(premultiplied(77, 128), premultiplied(51, 128), premultiplied(26, 128), eight(128)),
      ),
      Layout(
        "ARGB8 premultiplied",
        false,
        8,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value,
        bytes(128, 77, 51, 26),
        listOf(premultiplied(77, 128), premultiplied(51, 128), premultiplied(26, 128), eight(128)),
      ),
      Layout(
        "RGBA16 straight",
        false,
        16,
        STRAIGHT_LAST_16,
        little16(40000, 30000, 20000, 32768),
        listOf(40000, 30000, 20000, 32768).map { it / MAX_SAMPLE },
      ),
      Layout("RGBA16 straight transparent", false, 16, STRAIGHT_LAST_16, little16(40000, 30000, 20000, 0), TRANSPARENT),
      Layout(
        "RGBA16 premultiplied",
        false,
        16,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder16Little,
        little16(20000, 15000, 10000, 40000),
        listOf(
          premultiplied(20000, 40000),
          premultiplied(15000, 40000),
          premultiplied(10000, 40000),
          40000 / MAX_SAMPLE,
        ),
      ),
      Layout(
        "RGBA half floats premultiplied",
        false,
        16,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder16Little or
          kCGBitmapFloatComponents,
        // 0.375, 0.25, 0.125 and 0.5 as halves.
        little16(0x3600, 0x3400, 0x3000, 0x3800),
        listOf(0.75, 0.5, 0.25, 0.5),
      ),
      Layout(
        "RGBA floats premultiplied",
        false,
        32,
        PREMULTIPLIED_FLOATS,
        little32(0.375f, 0.25f, 0.125f, 0.5f),
        listOf(0.75, 0.5, 0.25, 0.5),
      ),
      // A color with no alpha under it, which divides to more than 1 unless the read writes it black.
      Layout(
        "RGBA floats premultiplied transparent",
        false,
        32,
        PREMULTIPLIED_FLOATS,
        little32(0.25f, 0.25f, 0.25f, 0f),
        TRANSPARENT,
      ),
      Layout(
        "RGBA floats premultiplied with a red that is not a number",
        false,
        32,
        PREMULTIPLIED_FLOATS,
        little32(Float.NaN, 0.25f, 0.125f, 0.5f),
        listOf(0.0, 0.5, 0.25, 0.5),
      ),
    )
}
