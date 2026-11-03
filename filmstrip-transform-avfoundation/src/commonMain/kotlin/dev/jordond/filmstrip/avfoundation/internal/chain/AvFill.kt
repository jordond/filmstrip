package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIColor

/**
 * The Core Image color a fill is drawn with.
 *
 * An SDR color lands as its own codes. It is named in the BT.709 code space `createCodeSpace`
 * gives, the one an SDR export's buffer is written in, so Core Image matching it in and the encode
 * matching it back out leave the packed components where they were. An HDR one is named in the
 * space it is going to instead, so the components carry a brightness rather than a bare fraction
 * and Core Image converts from something it knows.
 *
 * @param transfer The transfer function reaching the encoder, or null when the export writes SDR.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun fillCIColor(
  color: Int,
  transfer: HdrTransfer?,
): CIColor {
  val red = ((color shr 16) and 0xFF) / 255.0
  val green = ((color shr 8) and 0xFF) / 255.0
  val blue = (color and 0xFF) / 255.0
  val sdr = CIColor.colorWithRed(red, green = green, blue = blue, alpha = 1.0)
  if (transfer == null) return inCodes(red, green, blue) ?: sdr

  val space = CGColorSpaceCreateWithName(transfer.colorSpaceName()) ?: return sdr
  val signal = transfer.signalFromNits(authoredHdrNits(color))

  return CIColor.colorWithRed(
    signal[0].toDouble(),
    green = signal[1].toDouble(),
    blue = signal[2].toDouble(),
    alpha = 1.0,
    colorSpace = space,
  ) ?: sdr
}

@OptIn(ExperimentalForeignApi::class)
private fun inCodes(
  red: Double,
  green: Double,
  blue: Double,
): CIColor? {
  val space = ColorSpace.Bt709.createCodeSpace() ?: return null

  try {
    return CIColor.colorWithRed(red, green = green, blue = blue, alpha = 1.0, colorSpace = space)
  } finally {
    CGColorSpaceRelease(space)
  }
}

/**
 * Whether this platform can name the color space [transfer] is written in.
 *
 * A platform that cannot leaves [fillCIColor] on its sRGB fallback, which draws a fill dimmer than
 * it was authored rather than failing the export.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun HdrTransfer.hasColorSpace(): Boolean = CGColorSpaceCreateWithName(colorSpaceName()) != null

@OptIn(ExperimentalForeignApi::class)
private fun HdrTransfer.colorSpaceName(): CFStringRef? =
  when (this) {
    HdrTransfer.Pq -> kCGColorSpaceITUR_2100_PQ
    HdrTransfer.Hlg -> kCGColorSpaceITUR_2100_HLG
  }
