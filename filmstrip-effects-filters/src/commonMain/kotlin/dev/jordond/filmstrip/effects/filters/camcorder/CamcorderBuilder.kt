package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import kotlin.time.Duration

/**
 * Adds a [Camcorder] display described by [block].
 *
 * ```
 * camcorder {
 *   rec()
 *   date(RecordedAt(1997, 6, 14, 15, 32))
 *   clock()
 *   battery(0.25f)
 * }
 * ```
 *
 * Declared after a `vhs { }` at the same scope the display stays clean. Inside `vhs { camcorder { } }` it is recorded
 * on the tape instead.
 */
public fun EffectsBuilder.camcorder(block: CamcorderBuilder.() -> Unit): EffectsBuilder =
  add(CamcorderBuilder().apply(block).build())

/**
 * Describes a [Camcorder], one element per call in the order they stack.
 *
 * @property recordedAt The date and time the date and clock start at. [date] sets it too.
 * @property textSize The height of a line of text, as a fraction of the frame height.
 */
@FilmstripDsl
public class CamcorderBuilder internal constructor() {
  public var recordedAt: RecordedAt = Camcorder.DEFAULT_RECORDED_AT
  public var textSize: Float = Camcorder.DEFAULT_TEXT_SIZE
  private val elements = mutableListOf<OsdElement>()

  /**
   * Adds a blinking record dot and `REC`.
   */
  public fun rec(corner: OsdCorner = OsdCorner.TopLeft) {
    elements += OsdElement.Rec(corner)
  }

  /**
   * Adds a play icon and `PLAY`.
   */
  public fun play(corner: OsdCorner = OsdCorner.TopLeft) {
    transport(TransportMode.Play, corner)
  }

  /**
   * Adds a pause icon and `PAUSE`.
   */
  public fun pause(corner: OsdCorner = OsdCorner.TopLeft) {
    transport(TransportMode.Pause, corner)
  }

  /**
   * Adds the icon and label of the transport [mode].
   */
  public fun transport(
    mode: TransportMode,
    corner: OsdCorner = OsdCorner.TopLeft,
  ) {
    elements += OsdElement.Transport(mode, corner)
  }

  /**
   * Adds the date in [style], starting at [start], which also becomes [recordedAt].
   */
  public fun date(
    start: RecordedAt = recordedAt,
    style: DateStyle = DateStyle.MonthDayYear,
    corner: OsdCorner = OsdCorner.BottomLeft,
  ) {
    recordedAt = start
    elements += OsdElement.Date(style, corner)
  }

  /**
   * Adds the time of day in [style], read from [recordedAt].
   */
  public fun clock(
    style: ClockStyle = ClockStyle.TwelveHour,
    corner: OsdCorner = OsdCorner.BottomLeft,
  ) {
    elements += OsdElement.Clock(style, corner)
  }

  /**
   * Adds a tape counter starting at [start], with a frame count when [withFrames] is set.
   */
  public fun counter(
    start: Duration = Duration.ZERO,
    withFrames: Boolean = false,
    corner: OsdCorner = OsdCorner.TopRight,
  ) {
    elements += OsdElement.Counter(start, withFrames, corner)
  }

  /**
   * Adds a battery icon at [level], from `0f` to `1f`, blinking while low when [blinkWhenLow] is set.
   */
  public fun battery(
    level: Float,
    blinkWhenLow: Boolean = true,
    corner: OsdCorner = OsdCorner.TopRight,
  ) {
    elements += OsdElement.Battery(level, blinkWhenLow, corner)
  }

  /**
   * Adds the tape [speed] label.
   */
  public fun tape(
    speed: TapeSpeed,
    corner: OsdCorner = OsdCorner.TopRight,
  ) {
    elements += OsdElement.Tape(speed, corner)
  }

  /**
   * Adds fixed [text] in capitals.
   */
  public fun label(
    text: String,
    corner: OsdCorner = OsdCorner.BottomRight,
  ) {
    elements += OsdElement.Label(text, corner)
  }

  internal fun build(): Camcorder = Camcorder(elements.toList(), recordedAt, textSize)
}
