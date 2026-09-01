package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import dev.drewhamilton.poko.Poko

/**
 * What an overlay placement handle paints with.
 *
 * Built by [OverlayHandleDefaults.colors], which defaults every colour, so a host names only the ones it wants to
 * change.
 *
 * @property fill The wash over the overlay's own rectangle, which is what a handle with an empty content slot shows.
 * @property outline The rectangle's thin border.
 * @property handle The corner brackets a drag grabs.
 */
@Poko
public class OverlayHandleColors internal constructor(
  public val fill: Color,
  public val outline: Color,
  public val handle: Color,
)
