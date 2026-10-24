package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * Holds the stored samples the Apple overlay raster reads back to the ones a picture stores, however many bands the
 * read takes, and holds a value Core Image hands back as not a number to 0.
 *
 * The banded read goes through the raster's own [codeReader] and skips where that cannot be built, hardware or
 * software.
 */
@OptIn(ExperimentalForeignApi::class)
class StoredSamplesTest {
  @Test
  fun `a read in several bands lands every row where it is stored`() {
    val reader = readerOrSkip() ?: return
    val image = straightPicture()
    val codes = image.bitmapCodes()
    CGImageRelease(image)
    // Two rows a band, so seven rows read in three full bands and one of a single row.
    val read = codes.orFail("the straight picture").image.storedSamples(SIZE, reader, bandTexels = 2 * WIDTH)

    val expected =
      List(HEIGHT * WIDTH) { index -> texel(index % WIDTH, index / WIDTH) }.flatMap { (red, green, blue, alpha) ->
        if (alpha == 0) List(CHANNELS) { 0 } else listOf(red, green, blue, alpha).map { it * EIGHT_BIT_STEP }
      }
    assertContentEquals(expected, read.map { it.toInt() and MASK }, "the banded read moved or changed a texel")
  }

  @Test
  fun `a value that is not a number is written as 0`() {
    val floats =
      floatArrayOf(
        Float.NaN,
        0.25f,
        0.125f,
        0.5f,
        0.25f,
        0.25f,
        0.25f,
        Float.NaN,
        2f,
        -1f,
        0.25f,
        0.5f,
      )
    val samples = ShortArray(floats.size)
    floats.straightInto(samples, offset = 0, count = floats.size)

    val expected =
      listOf(0.0, 0.5, 0.25, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.5, 0.5).map { (it * MAX_SAMPLE).roundToInt() }
    assertContentEquals(expected, samples.map { it.toInt() and MASK })
  }

  private fun readerOrSkip(): CIContext? {
    val reader = codeReader
    if (reader == null) println("SKIPPED the banded read: no code reader can be built here, nothing asserted")
    return reader
  }

  /**
   * A [WIDTH] by [HEIGHT] RGBA picture with straight alpha, each texel holding the codes [texel] gives it.
   */
  private fun straightPicture(): CGImageRef {
    val bytes =
      ByteArray(WIDTH * HEIGHT * CHANNELS) { index ->
        val texel = index / CHANNELS
        texel(texel % WIDTH, texel / WIDTH)[index % CHANNELS].toByte()
      }
    val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
    val provider = CGDataProviderCreateWithCFData(data)
    CFRelease(data)
    val space = CGColorSpaceCreateDeviceRGB()
    val image =
      CGImageCreate(
        WIDTH.toULong(),
        HEIGHT.toULong(),
        BITS.toULong(),
        (CHANNELS * BITS).toULong(),
        (CHANNELS * WIDTH).toULong(),
        space,
        CGImageAlphaInfo.kCGImageAlphaLast.value,
        provider,
        null,
        false,
        CGColorRenderingIntent.kCGRenderingIntentDefault,
      )
    CGColorSpaceRelease(space)
    CGDataProviderRelease(provider)
    return assertNotNull(image, "Core Graphics made no picture")
  }

  private companion object {
    const val WIDTH = 5
    const val HEIGHT = 7
    const val BITS = 8
    const val CHANNELS = 4
    const val MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257
    const val MAX_SAMPLE = 65535.0
    val SIZE = Size(WIDTH, HEIGHT)

    // Every texel's codes differ, and every fourth along a diagonal has no alpha.
    fun texel(
      x: Int,
      y: Int,
    ): List<Int> =
      listOf(
        10 + 40 * x,
        5 + 30 * y,
        3 + 7 * (x + y),
        if ((x + y) % 4 == 3) 0 else 255 - 20 * y,
      )
  }
}
