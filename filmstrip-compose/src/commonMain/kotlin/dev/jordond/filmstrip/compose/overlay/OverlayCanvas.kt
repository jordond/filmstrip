package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt

/**
 * The space a composed overlay element is laid out in, the output frame as the app designs for it.
 *
 * The canvas is [width] wide and as tall as the output frame's aspect makes it, so a title card written in dp lays out
 * the same on a phone stage, a desktop window and the export. Content wraps its own size inside the canvas.
 *
 * @property width How wide the output frame reads to the content laid out on it. Must be positive.
 */
@Poko
@Immutable
public class OverlayCanvas(
  public val width: Dp = DEFAULT_WIDTH,
) {
  init {
    require(width > 0.dp && width.value.isFinite()) { "An overlay canvas must be a positive width, but was $width." }
  }

  public companion object {
    /**
     * The width a canvas is unless one is named, 360 dp.
     *
     * It is close to the width of a phone held upright, so content sized for a phone stage lays out the same here.
     */
    public val DEFAULT_WIDTH: Dp = 360.dp
  }
}

/**
 * The density every composed overlay element is laid out at, three pixels to the dp.
 *
 * The live element and the capture both lay content out at this density and draw that one layout scaled, so text
 * breaks on the same words in both.
 */
@InternalFilmstripApi
public const val OVERLAY_LAYOUT_DENSITY: Float = 3f

/**
 * The [Density] an element on this canvas is laid out at, with the font scale at one.
 */
@InternalFilmstripApi
public val OverlayLayoutDensity: Density = Density(OVERLAY_LAYOUT_DENSITY, 1f)

/**
 * The canvas width in layout pixels at [OVERLAY_LAYOUT_DENSITY].
 */
@InternalFilmstripApi
public fun OverlayCanvas.widthPx(): Int = (width.value * OVERLAY_LAYOUT_DENSITY).roundToInt().coerceAtLeast(1)

/**
 * The constraints content on this canvas is measured with for [output], as wide as the canvas and as tall as the
 * output frame's aspect makes it.
 *
 * @param output The output frame the element is drawn on, in pixels.
 */
@InternalFilmstripApi
public fun OverlayCanvas.constraintsFor(output: Size): Constraints =
  constraintsFor(if (output.width <= 0) 0f else output.width.toFloat() / output.height)

/**
 * The constraints content on this canvas is measured with on a frame of [aspect], as wide as the canvas and as tall
 * as the aspect makes it.
 *
 * @param aspect The output frame's width over its height. A frame with no aspect measures square.
 */
@InternalFilmstripApi
public fun OverlayCanvas.constraintsFor(aspect: Float): Constraints {
  val width = widthPx()
  val height = if (aspect > 0f) (width / aspect).roundToInt().coerceAtLeast(1) else width
  return Constraints(maxWidth = width, maxHeight = height)
}
