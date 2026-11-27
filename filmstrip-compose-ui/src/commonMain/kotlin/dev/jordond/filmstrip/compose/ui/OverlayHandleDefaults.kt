package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sizes and colors an overlay placement handle falls back to.
 *
 * The palette is neutral and reads no theme. A themed host passes its own colors to [colors] at the one call site that
 * builds the handle.
 */
public object OverlayHandleDefaults {
  /**
   * How long a corner bracket's arms are drawn, before a box too small to carry them shortens them.
   */
  public val HandleLength: Dp = 12.dp

  /**
   * The radius of the round knob a composed overlay element draws for its size handle and its turn handle.
   *
   * The knob is grabbed over [HandleTouchSize], which is wider than it is drawn.
   */
  public val KnobRadius: Dp = 5.dp

  /**
   * How far above an element's top edge its turn handle sits, on a stalk drawn from the middle of that edge.
   *
   * Measured on the screen, so the stalk keeps its length as the element is scaled.
   */
  public val StalkLength: Dp = 24.dp

  /**
   * The side of the square a size or turn handle is grabbed by, centred on its knob.
   *
   * Wider than the knob so a finger or a coarse pointer can still take it.
   */
  public val HandleTouchSize: Dp = 24.dp

  /**
   * The palette a handle draws with unless a host passes its own.
   *
   * Built once from [colors] and shared by every handle that falls back to it, across every recomposition a drag runs
   * through.
   */
  public val Palette: OverlayHandleColors = colors()

  /**
   * A neutral palette, and the one thing to override for a themed host.
   */
  public fun colors(
    fill: Color = Color(0x1FFFFFFF),
    outline: Color = Color(0xFFF2F3F5),
    handle: Color = Color(0xFFE8C15A),
  ): OverlayHandleColors =
    OverlayHandleColors(
      fill = fill,
      outline = outline,
      handle = handle,
    )
}
