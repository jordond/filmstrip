@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.time.Duration.Companion.seconds

// What the Film kernel tests build a frame from and read one back with, in the codes of the BT.709 buffer the way the
// chain hands a frame over and takes it back. Rows of a bitmap run from the top.

internal const val FILM_CHANNELS = 4

internal fun filmAttributes(
  width: Int,
  height: Int,
) = Attributes(
  inputSize = Size(width, height),
  outputSize = Size(width, height),
  layoutSize = Size(width, height),
  colorSpace = ColorSpace.Bt709,
  hdrTransfer = null,
  frameRate = 30f,
  span = TimeRange(0.seconds, 4.seconds),
)

// The bounds read default to the whole frame, and a tile reads a part of it from its bottom left corner.
@OptIn(ExperimentalForeignApi::class)
internal fun filmRendered(
  image: CIImage,
  width: Int,
  height: Int,
  originX: Int = 0,
  originY: Int = 0,
  boundsWidth: Int = width,
  boundsHeight: Int = height,
): FloatArray {
  val values = FloatArray(boundsWidth * boundsHeight * FILM_CHANNELS)
  val space = ColorSpace.Bt709.createCodeSpace()
  try {
    values.usePinned { pinned ->
      floatContext().render(
        image,
        toBitmap = pinned.addressOf(0),
        rowBytes = (boundsWidth * FILM_CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(originX.toDouble(), originY.toDouble(), boundsWidth.toDouble(), boundsHeight.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    }
  } finally {
    CGColorSpaceRelease(space)
  }
  return values
}

// Rows of the bitmap run from the top, where [pixel] counts its y.
@OptIn(ExperimentalForeignApi::class)
internal fun filmImageOf(
  width: Int,
  height: Int,
  pixel: (Int, Int) -> FloatArray,
): CIImage {
  val values = FloatArray(width * height * FILM_CHANNELS)
  for (y in 0 until height) {
    for (x in 0 until width) {
      pixel(x, y).copyInto(values, (y * width + x) * FILM_CHANNELS)
    }
  }
  val space = ColorSpace.Bt709.createCodeSpace()
  try {
    val data =
      values.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
      }
    return CIImage.imageWithBitmapData(
      data,
      bytesPerRow = (width * FILM_CHANNELS * Float.SIZE_BYTES).convert(),
      size = CGSizeMake(width.toDouble(), height.toDouble()),
      format = kCIFormatRGBAf,
      colorSpace = space,
    )
  } finally {
    CGColorSpaceRelease(space)
  }
}

// Core Image works in half floats unless told otherwise, whose rounding around 0.5 is wider than a read of 1e-4.
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
private fun floatContext(): CIContext {
  val options =
    NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
  return CIContext.contextWithOptions(options as Map<Any?, *>)
}
