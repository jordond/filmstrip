package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import dev.drewhamilton.poko.Poko

/**
 * What a timeline paints with.
 *
 * Built by [FilmstripTimelineDefaults.colors], which defaults every colour, so a host names only the ones it wants to
 * change.
 *
 * @property tile The ground behind a tile whose frame has not arrived.
 * @property tileDivider The hairline between two tiles.
 * @property ruler The ruler's tick marks.
 * @property rulerLabel The ruler's time labels.
 * @property playhead The playhead line and its knob.
 * @property trimHandle The body of a trim handle.
 * @property trimHandleGrip The grip drawn inside a trim handle.
 * @property trimScrim What covers the parts of the strip the trim leaves out.
 */
@Poko
public class TimelineColors internal constructor(
  public val tile: Color,
  public val tileDivider: Color,
  public val ruler: Color,
  public val rulerLabel: Color,
  public val playhead: Color,
  public val trimHandle: Color,
  public val trimHandleGrip: Color,
  public val trimScrim: Color,
)
