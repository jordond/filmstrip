package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.signalFromNits
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIColor

/**
 * The Core Image colour a fill is drawn with.
 *
 * An SDR colour goes in as authored, since `colorWithRed:green:blue:alpha:` builds in sRGB and
 * packed ARGB components match that directly. An HDR one is named in the space it is going to
 * instead, so the components carry a brightness rather than a bare fraction and Core Image converts
 * from something it knows.
 *
 * @param transfer The transfer function reaching the encoder, or null when the export writes SDR.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun fillCIColor(
  color: Int,
  transfer: HdrTransfer?,
): CIColor {
  val sdr =
    CIColor.colorWithRed(
      ((color shr 16) and 0xFF) / 255.0,
      green = ((color shr 8) and 0xFF) / 255.0,
      blue = (color and 0xFF) / 255.0,
      alpha = 1.0,
    )
  if (transfer == null) return sdr

  val space = CGColorSpaceCreateWithName(transfer.colorSpaceName()) ?: return sdr
  val signal = transfer.signalFromNits(hdrFillNits(color))

  return CIColor.colorWithRed(
    signal[0].toDouble(),
    green = signal[1].toDouble(),
    blue = signal[2].toDouble(),
    alpha = 1.0,
    colorSpace = space,
  ) ?: sdr
}

/**
 * Whether this platform can name the colour space [transfer] is written in.
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
