package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.interaction.CropConstraint

/**
 * The sizes, colours and constraint a crop overlay falls back to.
 *
 * The palette is neutral and self-contained rather than themed, because this module draws with foundation alone. A
 * themed host passes its own colours to [colors] at the one call site that builds the overlay.
 */
public object CropOverlayDefaults {
  /**
   * How long a corner bracket's arms and an edge grip are drawn.
   */
  public val HandleLength: Dp = 20.dp

  /**
   * How wide a handle answers to a finger, which is wider than it is drawn.
   */
  public val TouchSize: Dp = 44.dp

  /**
   * The palette an overlay draws with unless a host passes its own.
   *
   * A single instance rather than a call to [colors], so a component falling back to it allocates nothing on the
   * recompositions a drag runs through.
   */
  public val Palette: CropColors = colors()

  /**
   * How narrow a crop may get by default, as a fraction of the frame.
   */
  public val MinWidth: Float = 0.1f

  /**
   * How short a crop may get by default, as a fraction of the frame.
   */
  public val MinHeight: Float = 0.1f

  /**
   * What a crop gesture may produce unless a caller says otherwise.
   */
  public val Constraint: CropConstraint = CropConstraint.Free(MinWidth, MinHeight)

  /**
   * A neutral palette, and the one thing to override for a themed host.
   */
  public fun colors(
    scrim: Color = Color(0xB8000000),
    outline: Color = Color(0xFFF2F3F5),
    handle: Color = Color(0xFFE8C15A),
    grid: Color = Color(0x80FFFFFF),
  ): CropColors =
    CropColors(
      scrim = scrim,
      outline = outline,
      handle = handle,
      grid = grid,
    )
}
