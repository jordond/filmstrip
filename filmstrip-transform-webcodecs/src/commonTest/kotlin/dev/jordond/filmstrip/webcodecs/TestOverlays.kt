package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels

/**
 * The color channels of an ARGB int, as the codes a frame read back is compared with.
 */
internal fun rgbOf(argb: Int): Rgb {
  val (red, green, blue) = AuthoredColors.channelsOf(argb)
  return Rgb(red, green, blue)
}

/**
 * Where a source over draw of [overlay] at [opacity] leaves this color, worked on the codes themselves.
 */
internal fun Rgb.towards(
  overlay: Rgb,
  opacity: Float,
): Rgb =
  Rgb(
    OverlayPixels.sourceOver(red, overlay.red, opacity),
    OverlayPixels.sourceOver(green, overlay.green, opacity),
    OverlayPixels.sourceOver(blue, overlay.blue, opacity),
  )

internal val NormalizedRect.middleX: Double get() = (left + right) / 2.0

internal val NormalizedRect.middleY: Double get() = (top + bottom) / 2.0
