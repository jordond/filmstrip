package dev.jordond.filmstrip.effects.filters.camcorder

import dev.drewhamilton.poko.Poko

/**
 * The date and time a [Camcorder] shows at the start of the composition.
 *
 * A wall clock reading with no timezone. The date and clock on screen read this plus the composition time, carried
 * across minutes, days, months and years on the Gregorian calendar. A reading that would pass the last second of
 * 9999 holds at 9999-12-31 23:59:59.
 *
 * @property year The year, from `1` to `9999`.
 * @property month The month, from `1` for January to `12`.
 * @property day The day of the month, from `1` to the length of [month] in [year].
 * @property hour The hour, from `0` to `23`.
 * @property minute The minute, from `0` to `59`.
 * @property second The second, from `0` to `59`.
 * @throws IllegalArgumentException when a value is outside its range.
 */
@Poko
public class RecordedAt(
  public val year: Int,
  public val month: Int,
  public val day: Int,
  public val hour: Int = 0,
  public val minute: Int = 0,
  public val second: Int = 0,
) {
  init {
    require(year in 1..MAX_YEAR) { "A recorded year has to be within 1 to $MAX_YEAR, not $year." }
    require(month in 1..MONTHS) { "A recorded month has to be within 1 to $MONTHS, not $month." }
    val length = monthLength(year, month)
    require(day in 1..length) { "Month $month of $year has days 1 to $length, not $day." }
    require(hour in 0 until HOURS) { "A recorded hour has to be within 0 to 23, not $hour." }
    require(minute in 0 until MINUTES) { "A recorded minute has to be within 0 to 59, not $minute." }
    require(second in 0 until SECONDS) { "A recorded second has to be within 0 to 59, not $second." }
  }

  internal fun plusSeconds(elapsed: Long): RecordedAt {
    require(elapsed >= 0) { "Elapsed seconds can not be negative, not $elapsed." }
    val ofDay = hour * SECONDS_PER_HOUR + minute * SECONDS + second + elapsed
    var days = ofDay / SECONDS_PER_DAY
    val left = ofDay % SECONDS_PER_DAY
    var y = year
    var m = month
    var d = day
    while (days > 0) {
      val remaining = monthLength(y, m) - d
      if (days <= remaining) {
        d += days.toInt()
        days = 0
      } else {
        days -= remaining + 1
        d = 1
        m += 1
        if (m > MONTHS) {
          if (y == MAX_YEAR) return RecordedAt(MAX_YEAR, MONTHS, LAST_DAY, HOURS - 1, MINUTES - 1, SECONDS - 1)
          m = 1
          y += 1
        }
      }
    }
    return RecordedAt(
      year = y,
      month = m,
      day = d,
      hour = (left / SECONDS_PER_HOUR).toInt(),
      minute = (left % SECONDS_PER_HOUR / SECONDS).toInt(),
      second = (left % SECONDS).toInt(),
    )
  }
}

internal fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

internal fun monthLength(
  year: Int,
  month: Int,
): Int =
  when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
  }

private const val MAX_YEAR = 9999
private const val MONTHS = 12
private const val LAST_DAY = 31
private const val HOURS = 24
private const val MINUTES = 60
private const val SECONDS = 60
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
