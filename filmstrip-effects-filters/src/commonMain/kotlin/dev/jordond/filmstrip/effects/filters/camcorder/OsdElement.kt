package dev.jordond.filmstrip.effects.filters.camcorder

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import kotlin.time.Duration

/**
 * One line of a [Camcorder]'s on screen display.
 *
 * Each element sits in its [corner]. Elements sharing a corner stack in the order they are declared, the first one on
 * top.
 */
public sealed interface OsdElement {
  /**
   * The corner of the frame this element sits in.
   */
  public val corner: OsdCorner

  /**
   * A red record dot blinking once a second, followed by `REC`.
   */
  @Poko
  public class Rec(
    override val corner: OsdCorner = OsdCorner.TopLeft,
  ) : OsdElement

  /**
   * The tape transport's [mode] as an icon and its label, like `PLAY` or `PAUSE`.
   */
  @Poko
  public class Transport(
    public val mode: TransportMode,
    override val corner: OsdCorner = OsdCorner.TopLeft,
  ) : OsdElement

  /**
   * The date in [style], reading [Camcorder.recordedAt] plus the composition time.
   */
  @Poko
  public class Date(
    public val style: DateStyle = DateStyle.MonthDayYear,
    override val corner: OsdCorner = OsdCorner.BottomLeft,
  ) : OsdElement

  /**
   * The time of day in [style], reading [Camcorder.recordedAt] plus the composition time.
   */
  @Poko
  public class Clock(
    public val style: ClockStyle = ClockStyle.TwelveHour,
    override val corner: OsdCorner = OsdCorner.BottomLeft,
  ) : OsdElement

  /**
   * A tape counter reading [start] plus the composition time, as `0:00:00`.
   *
   * @property start Where the counter starts. Never negative.
   * @property withFrames Whether a frame count follows the seconds, as `0:00:00:00`, counted at the output frame rate.
   * @throws IllegalArgumentException when [start] is negative or infinite.
   */
  @Poko
  public class Counter(
    public val start: Duration = Duration.ZERO,
    public val withFrames: Boolean = false,
    override val corner: OsdCorner = OsdCorner.TopRight,
  ) : OsdElement {
    init {
      require(!start.isNegative() && start.isFinite()) { "A counter start can not be negative, not $start." }
    }
  }

  /**
   * A four step battery icon.
   *
   * @property level How full the battery is, from `0f` to `1f`. It shows as zero to three bars, rounded up.
   * @property blinkWhenLow Whether the icon blinks once a second while it shows one bar or none.
   * @throws IllegalArgumentException when [level] is outside `0f..1f`.
   */
  @Poko
  public class Battery(
    public val level: Float,
    public val blinkWhenLow: Boolean = true,
    override val corner: OsdCorner = OsdCorner.TopRight,
  ) : OsdElement {
    init {
      require(level in 0f..1f) { "A battery level has to be within 0 to 1, not $level." }
    }
  }

  /**
   * The tape [speed] as its label, `SP`, `LP` or `EP`.
   */
  @Poko
  public class Tape(
    public val speed: TapeSpeed,
    override val corner: OsdCorner = OsdCorner.TopRight,
  ) : OsdElement

  /**
   * Fixed [text] in capitals.
   *
   * @property text The text, drawn from `A` to `Z`, `0` to `9`, space and `: . / - ' +`.
   * @throws IllegalArgumentException when [text] holds a character outside that set, naming it.
   */
  @Poko
  public class Label(
    public val text: String,
    override val corner: OsdCorner = OsdCorner.BottomRight,
  ) : OsdElement {
    init {
      OsdFont.requireDrawable(text)
    }
  }
}

/**
 * A corner of the frame, inset by the title safe margin.
 */
public enum class OsdCorner {
  /**
   * The top left corner, lines aligned to its left edge.
   */
  TopLeft,

  /**
   * The top right corner, lines aligned to its right edge.
   */
  TopRight,

  /**
   * The bottom left corner, lines aligned to its left edge.
   */
  BottomLeft,

  /**
   * The bottom right corner, lines aligned to its right edge.
   */
  BottomRight,
}

/**
 * What the tape transport is doing, shown by [OsdElement.Transport].
 */
public enum class TransportMode {
  /**
   * A play triangle and `PLAY`.
   */
  Play,

  /**
   * Two pause bars and `PAUSE`.
   */
  Pause,

  /**
   * A stop square and `STOP`.
   */
  Stop,

  /**
   * Two triangles pointing left and `REW`.
   */
  Rewind,

  /**
   * Two triangles pointing right and `FF`.
   */
  FastForward,
}

/**
 * The order an [OsdElement.Date] shows its parts in.
 */
public enum class DateStyle {
  /**
   * `JUN.14 1997`.
   */
  MonthDayYear,

  /**
   * `14.JUN 1997`.
   */
  DayMonthYear,

  /**
   * `1997.06.14`.
   */
  YearMonthDay,
}

/**
 * How an [OsdElement.Clock] shows the time of day.
 */
public enum class ClockStyle {
  /**
   * `PM 3:32:05`, with noon as `PM 12` and midnight as `AM 12`.
   */
  TwelveHour,

  /**
   * `15:32:05`.
   */
  TwentyFourHour,
}
