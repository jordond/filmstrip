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
