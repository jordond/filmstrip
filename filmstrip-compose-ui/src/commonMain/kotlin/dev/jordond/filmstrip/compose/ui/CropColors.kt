package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import dev.drewhamilton.poko.Poko

/**
 * What a crop overlay paints with.
 *
 * Built by [CropOverlayDefaults.colors], which defaults every colour, so a host names only the ones it wants to change.
 *
 * @property scrim What covers the frame outside the selected rectangle.
 * @property outline The rectangle's thin border.
 * @property handle The corner brackets and edge grips.
 * @property grid The rule-of-thirds guides drawn while a drag is in flight.
 */
@Poko
public class CropColors internal constructor(
  public val scrim: Color,
  public val outline: Color,
  public val handle: Color,
  public val grid: Color,
)
