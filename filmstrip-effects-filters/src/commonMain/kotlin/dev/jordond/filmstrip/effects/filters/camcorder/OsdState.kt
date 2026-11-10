package dev.jordond.filmstrip.effects.filters.camcorder

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Duration

// What a camcorder display shows at one moment, line by line. It changes at most on a blink edge, a second or a
// counted frame, so a backend caches its raster by this and only redraws on a change.
internal data class OsdState(
  val lines: List<OsdLine>,
)

internal data class OsdLine(
  val corner: OsdCorner,
  val runs: List<OsdRun>,
)

// A stretch of text in one colour. A run with no colour keeps its space but draws nothing, which is how a blink hides.
internal data class OsdRun(
  val text: String,
  val colour: Int,
)

// The square wave a blink follows, on for the first half of each period.
internal fun blinkOn(time: Duration): Boolean = time.inWholeMicroseconds.coerceAtLeast(0) / BLINK_HALF_MICROS % 2 == 0L

// How long each half of a blink lasts at OsdParams.BLINK_RATE, in microseconds.
internal val BLINK_HALF_MICROS: Long = (MICROS_PER_SECOND / (2 * OsdParams.BLINK_RATE)).roundToLong()

internal fun osdStateAt(
  spec: Camcorder,
  time: Duration,
  frameRate: Double,
): OsdState {
  val micros = time.inWholeMicroseconds.coerceAtLeast(0)
  val wholeSeconds = micros / MICROS_PER_SECOND
  val blink = blinkOn(time)
  val lines =
    spec.elements.map { element ->
      val runs =
        when (element) {
          is OsdElement.Rec -> listOf(OsdRun(OsdFont.RECORD.toString(), recordColour(blink)), text(" REC"))
          is OsdElement.Transport -> transportRuns(element.mode)
          is OsdElement.Date -> listOf(text(dateText(spec.recordedAt.plusSeconds(wholeSeconds), element.style)))
          is OsdElement.Clock -> listOf(text(clockText(spec.recordedAt.plusSeconds(wholeSeconds), element.style)))
          is OsdElement.Counter -> counterRuns(element, micros, frameRate)
          is OsdElement.Battery -> listOf(batteryRun(element, blink))
          is OsdElement.Tape -> listOf(text(element.speed.name))
          is OsdElement.Label -> listOf(text(element.text))
        }
      OsdLine(element.corner, runs)
    }
  return OsdState(lines)
}

internal fun dateText(
  at: RecordedAt,
  style: DateStyle,
): String {
  val month = MONTH_NAMES[at.month - 1]
  val day = at.day.toString().padStart(2, ' ')
  val year = at.year.toString().padStart(4, '0')
  return when (style) {
    DateStyle.MonthDayYear -> "$month.$day $year"
    DateStyle.DayMonthYear -> "$day.$month $year"
    DateStyle.YearMonthDay -> "$year.${twoDigits(at.month)}.${twoDigits(at.day)}"
  }
}

internal fun clockText(
  at: RecordedAt,
  style: ClockStyle,
): String =
  when (style) {
    ClockStyle.TwelveHour -> {
      val half = if (at.hour < NOON) "AM" else "PM"
      val hour = (at.hour % NOON).takeIf { it != 0 } ?: NOON
      "$half $hour:${twoDigits(at.minute)}:${twoDigits(at.second)}"
    }
    ClockStyle.TwentyFourHour -> {
      "${twoDigits(at.hour)}:${twoDigits(at.minute)}:${twoDigits(at.second)}"
    }
  }

internal fun counterText(
  counter: OsdElement.Counter,
  micros: Long,
  frameRate: Double,
): String {
  val total = counter.start.inWholeMicroseconds + micros
  val seconds = total / MICROS_PER_SECOND
  val clock = "${seconds / SECONDS_PER_HOUR}:${twoDigits(seconds / 60 % 60)}:${twoDigits(seconds % 60)}"
  if (!counter.withFrames) return clock
  return "$clock:${frameWithinSecond(total, frameRate).toString().padStart(frameDigits(frameRate), '0')}"
}

// The digits a frame count pads to at [frameRate], enough for the last frame of a second so the count keeps one width.
internal fun frameDigits(frameRate: Double): Int =
  max(MIN_FRAME_DIGITS, (ceil(frameRate).toLong() - 1).toString().length)

// A counter as its clock and, when it counts frames, the frame count as a run of its own after the last colon.
private fun counterRuns(
  counter: OsdElement.Counter,
  micros: Long,
  frameRate: Double,
): List<OsdRun> {
  val shown = counterText(counter, micros, frameRate)
  if (!counter.withFrames) return listOf(text(shown))
  val split = shown.lastIndexOf(':') + 1
  return listOf(text(shown.substring(0, split)), text(shown.substring(split)))
}

// [state] with the frame count of every counter in [spec] that shows one kept in place but hidden. A hidden count reads
// zeros, which every digit matches the width of, so the state only changes when something shown does.
internal fun framesHidden(
  spec: Camcorder,
  state: OsdState,
): OsdState =
  OsdState(
    state.lines.mapIndexed { index, line ->
      val element = spec.elements[index]
      if (element is OsdElement.Counter && element.withFrames) {
        val count = line.runs.last()
        line.copy(runs = line.runs.dropLast(1) + OsdRun("0".repeat(count.text.length), HIDDEN))
      } else {
        line
      }
    },
  )

// [state] with every run hidden but the frame counts on [frameLines], each kept in place.
internal fun framesOnly(
  state: OsdState,
  frameLines: List<Int>,
): OsdState =
  OsdState(
    state.lines.mapIndexed { index, line ->
      val count = if (index in frameLines) line.runs.last() else null
      line.copy(runs = line.runs.map { if (it === count) it else it.copy(colour = HIDDEN) })
    },
  )

// [state] with the frame counts on [frameLines] hidden in place and every other run as it was.
internal fun framesCleared(
  state: OsdState,
  frameLines: List<Int>,
): OsdState =
  if (frameLines.isEmpty()) {
    state
  } else {
    OsdState(
      state.lines.mapIndexed { index, line ->
        if (index in
          frameLines
        ) {
          line.copy(runs = line.runs.dropLast(1) + line.runs.last().copy(colour = HIDDEN))
        } else {
          line
        }
      },
    )
  }

// The frame a time falls in within its second, counted at [frameRate]. A frame's timestamp rounded to the microsecond
// can land a hair before the frame starts, so the count allows a sliver of a frame.
internal fun frameWithinSecond(
  micros: Long,
  frameRate: Double,
): Long {
  val fraction = (micros % MICROS_PER_SECOND).toDouble() / MICROS_PER_SECOND
  val last = ceil(frameRate).toLong() - 1
  return floor(fraction * frameRate + FRAME_MARGIN).toLong().coerceAtMost(last)
}

// The bars a battery icon shows, its level rounded up to thirds.
internal fun batteryBars(level: Float): Int =
  ceil(level * OsdFont.BATTERY_BARS - LEVEL_MARGIN).toInt().coerceIn(0, OsdFont.BATTERY_BARS)

private fun transportRuns(mode: TransportMode): List<OsdRun> {
  val (icon, label) =
    when (mode) {
      TransportMode.Play -> OsdFont.PLAY to "PLAY"
      TransportMode.Pause -> OsdFont.PAUSE to "PAUSE"
      TransportMode.Stop -> OsdFont.STOP to "STOP"
      TransportMode.Rewind -> OsdFont.REWIND to "REW"
      TransportMode.FastForward -> OsdFont.FAST_FORWARD to "FF"
    }
  return listOf(text(icon.toString()), text(" $label"))
}

private fun batteryRun(
  battery: OsdElement.Battery,
  blink: Boolean,
): OsdRun {
  val bars = batteryBars(battery.level)
  val hidden = battery.blinkWhenLow && bars <= 1 && !blink
  return OsdRun(OsdFont.battery(bars).toString(), if (hidden) HIDDEN else OsdParams.TEXT_COLOUR)
}

private fun recordColour(blink: Boolean): Int = if (blink) OsdParams.RECORD_COLOUR else HIDDEN

private fun text(value: String): OsdRun = OsdRun(value, OsdParams.TEXT_COLOUR)

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')

internal const val HIDDEN = 0
internal const val MICROS_PER_SECOND = 1_000_000L
private const val SECONDS_PER_HOUR = 3_600L
private const val NOON = 12
internal const val FRAME_MARGIN = 1e-3
private const val LEVEL_MARGIN = 1e-6
private const val MIN_FRAME_DIGITS = 2

private val MONTH_NAMES =
  listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
