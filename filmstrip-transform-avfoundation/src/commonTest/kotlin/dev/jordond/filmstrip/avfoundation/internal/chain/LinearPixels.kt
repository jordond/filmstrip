package dev.jordond.filmstrip.avfoundation.internal.chain

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceLinearSRGB
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs
import kotlin.test.fail

/**
 * A Core Image image rendered into float RGBA in linear sRGB, read a pixel at a time.
 *
 * Linear and unclamped, so a value reads back as the light Core Image computed rather than as an encoded code, and a
 * gain can be compared against the shared function that produced it. Rows come back in whatever order Core Image
 * writes them, so a caller only reads along a row it knows is symmetric top to bottom.
 */
internal class LinearPixels(
  val width: Int,
  val height: Int,
  private val values: FloatArray,
) {
  /**
   * The premultiplied red, green, blue and alpha of the pixel at ([x], [y]).
   */
  fun at(
    x: Int,
    y: Int,
  ): List<Float> {
    val offset = (y * width + x) * CHANNELS
    return values.slice(offset until offset + CHANNELS)
  }

  companion object {
    const val CHANNELS = 4
  }
}

/**
 * Renders the [width] by [height] rect at the origin of this image.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.linearPixels(
  width: Int,
  height: Int,
): LinearPixels {
  val values = FloatArray(width * height * LinearPixels.CHANNELS)
  val space = CGColorSpaceCreateWithName(kCGColorSpaceLinearSRGB) ?: fail("no linear sRGB color space")
  try {
    values.usePinned { pinned ->
      CIContext().render(
        this,
        toBitmap = pinned.addressOf(0),
        rowBytes = (width * LinearPixels.CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    }
  } finally {
    CGColorSpaceRelease(space)
  }
  return LinearPixels(width, height, values)
}

/**
 * This image's extent as origin x, origin y, width and height.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.rect(): List<Double> = extent.useContents { listOf(origin.x, origin.y, size.width, size.height) }

internal fun assertNear(
  actual: List<Double>,
  expected: List<Double>,
  tolerance: Double = 1e-6,
) {
  withClue("expected $expected but was $actual") {
    (actual.size == expected.size && actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }).shouldBeTrue()
  }
}

internal fun assertPixel(
  actual: List<Float>,
  expected: List<Float>,
  label: String,
  tolerance: Float = 2e-3f,
) {
  withClue("expected $label to be $expected but was $actual") {
    actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }.shouldBeTrue()
  }
}
