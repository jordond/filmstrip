package dev.jordond.filmstrip.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.interaction.TrimConstraint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The sizes, colours and formats a timeline falls back to.
 *
 * The palette is neutral and self-contained rather than themed, because this module draws with foundation alone. A
 * themed host passes its own colours to [colors] at the one call site that builds the timeline.
 */
public object FilmstripTimelineDefaults {
  /**
   * How tall the strip of tiles is drawn.
   */
  public val StripHeight: Dp = 72.dp

  /**
   * How wide one tile is drawn.
   */
  public val TileWidth: Dp = 46.dp

  /**
   * How tall the ruler is drawn.
   *
   * Tall enough to press, because the ruler is the timeline's scrub surface.
   */
  public val RulerHeight: Dp = 28.dp

  /**
   * How wide the drawn body of a trim handle is.
   */
  public val HandleWidth: Dp = 14.dp

  /**
   * How wide a trim handle answers to a finger, which is wider than it is drawn.
   */
  public val HandleTouchWidth: Dp = 44.dp

  /**
   * How wide the playhead's line is drawn.
   */
  public val PlayheadWidth: Dp = 2.dp

  /**
   * How wide the playhead's knob is drawn.
   */
  public val PlayheadKnobSize: Dp = 10.dp

  /**
   * How close two ruler ticks may be drawn before the ruler steps up to a coarser unit.
   *
   * A [Dp], because the constraint is a legible distance on the screen rather than a count of pixels, and one pixel is
   * a third of that on a dense phone.
   */
  public val MinTickSpacing: Dp = 56.dp

  /**
   * How short a trim may get by default.
   */
  public val MinTrimDuration: Duration = 200.milliseconds

  /**
   * How far an arrow key seeks by default.
   */
  public val KeyStep: Duration = 1.seconds

  /**
   * The palette a timeline draws with unless a host passes its own.
   *
   * A single instance rather than a call to [colors], so a component falling back to it allocates nothing on the
   * recompositions a scrubbing timeline runs through.
   */
  public val Palette: TimelineColors = colors()

  /**
   * What a trim gesture may produce unless a caller says otherwise.
   */
  public val Trim: TrimConstraint = TrimConstraint.MinDuration(MinTrimDuration)

  /**
   * A neutral palette, and the one thing to override for a themed host.
   */
  public fun colors(
    tile: Color = Color(0xFF2A2D33),
    tileDivider: Color = Color(0x33000000),
    ruler: Color = Color(0xFF8A8F98),
    rulerLabel: Color = Color(0xFF8A8F98),
    playhead: Color = Color(0xFFF2F3F5),
    trimHandle: Color = Color(0xFFE8C15A),
    trimHandleGrip: Color = Color(0xFF1B1D21),
    trimScrim: Color = Color(0xB8000000),
  ): TimelineColors =
    TimelineColors(
      tile = tile,
      tileDivider = tileDivider,
      ruler = ruler,
      rulerLabel = rulerLabel,
      playhead = playhead,
      trimHandle = trimHandle,
      trimHandleGrip = trimHandleGrip,
      trimScrim = trimScrim,
    )

  /**
   * Formats [time] for a ruler whose ticks are [interval] apart.
   *
   * Tenths appear only below a second, and the hours field only once there are any, so a label never carries a digit
   * the zoom cannot resolve.
   *
   * @param time The source time to label.
   * @param interval How far apart the ruler's ticks sit.
   * @return The label, as `m:ss` , `h:mm:ss` or `m:ss.t` .
   */
  public fun clockLabel(
    time: Duration,
    interval: Duration,
  ): String {
    val total = time.inWholeMilliseconds.coerceAtLeast(0)
    val hours = total / 3_600_000
    val minutes = total / 60_000 % 60
    val seconds = total / 1_000 % 60
    val tenths = total % 1_000 / 100

    val head = if (hours > 0) "$hours:${minutes.pad()}" else "$minutes"
    val body = "$head:${seconds.pad()}"
    return if (interval < 1.seconds) "$body.$tenths" else body
  }

  private fun Long.pad(): String = toString().padStart(2, '0')
}
