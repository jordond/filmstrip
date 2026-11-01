package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.roundToInt

/**
 * The size, in pixels, a rasteriser draws a face at to measure its cap height before scaling it to
 * [capHeightOn].
 */
internal const val CAP_PROBE_SIZE: Float = 100f

/**
 * How tall this style's capitals stand on [frame], in pixels.
 *
 * [TextStyle.fontSize] names a cap height as a fraction of frame height, so this is the size every
 * platform fits its face to.
 */
internal fun TextStyle.capHeightOn(frame: Size): Float = fontSize * frame.height

/**
 * The width, in whole pixels, at which this style's text wraps on [frame].
 *
 * Never less than one pixel, so a zero [TextStyle.maxWidth] still hands a layout engine a width it
 * accepts.
 */
internal fun TextStyle.wrapWidthOn(frame: Size): Int = (maxWidth * frame.width).roundToInt().coerceAtLeast(1)

/**
 * The margin, in pixels, a plate carries on every side of the text on [frame].
 *
 * Zero when the style has no [TextStyle.backgroundColor]. Otherwise a fixed share of [capHeightOn], so
 * the plate grows with the text rather than by a fixed number of pixels.
 */
internal fun TextStyle.platePaddingOn(frame: Size): Int =
  if (backgroundColor == null) 0 else (capHeightOn(frame) * PLATE_PADDING).roundToInt()

/**
 * The side, in pixels, of a raster that lays [padding] on both ends of a [block] of text.
 *
 * Counted in a [Long], since [platePaddingOn] saturates at [Int.MAX_VALUE] for a huge font size and two of those do not
 * fit in an [Int]. A [block] a backend measured in a [Long] may be past an [Int] already, and a side past the largest
 * [Long] is held there rather than wrapping negative. A backend checks this side with [textRasterFits] before it
 * narrows it to make a raster.
 */
internal fun plateSide(
  block: Long,
  padding: Int,
): Long = if (block > Long.MAX_VALUE - 2L * padding) Long.MAX_VALUE else block + 2L * padding

private const val PLATE_PADDING = 0.3f
