package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.unit.Dp
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.compose.FilmstripFramesDefaults
import dev.jordond.filmstrip.compose.ui.interaction.TrimConstraint

/**
 * Everything optional about a [FilmstripTimeline].
 *
 * Every field has a default, so a caller names only what it is changing.
 *
 * @property stripHeight How tall the strip of tiles is drawn.
 * @property tileWidth How wide one tile is drawn, which is what sets how many there are.
 * @property rulerHeight How tall the ruler is drawn.
 * @property showRuler Whether the ruler is drawn. It is also the timeline's scrub surface, so a timeline without one
 * cannot be scrubbed.
 * @property showPlayhead Whether the playhead is drawn.
 * @property followPlayhead Whether the strip scrolls itself to keep the playhead on screen.
 * @property trimConstraint What a trim gesture is allowed to produce.
 * @property maxBytes How many bytes of decoded frames the strip holds.
 * @property overscan How many tiles either side of the visible ones are kept ready.
 * @property pinchZoom Whether a pinch gesture steps the zoom ladder.
 * @property wheelZoom Whether a modified scroll wheel steps the zoom ladder.
 */
@Poko
public class TimelineOptions(
  public val stripHeight: Dp = FilmstripTimelineDefaults.StripHeight,
  public val tileWidth: Dp = FilmstripTimelineDefaults.TileWidth,
  public val rulerHeight: Dp = FilmstripTimelineDefaults.RulerHeight,
  public val showRuler: Boolean = true,
  public val showPlayhead: Boolean = true,
  public val followPlayhead: Boolean = true,
  public val trimConstraint: TrimConstraint = FilmstripTimelineDefaults.Trim,
  public val maxBytes: Long = FilmstripFramesDefaults.MaxBytes,
  public val overscan: Int = FilmstripFramesDefaults.Overscan,
  public val pinchZoom: Boolean = true,
  public val wheelZoom: Boolean = true,
) {
  public companion object {
    /**
     * Every default, as a single instance a timeline can fall back to without allocating one.
     */
    public val Default: TimelineOptions = TimelineOptions()
  }
}
