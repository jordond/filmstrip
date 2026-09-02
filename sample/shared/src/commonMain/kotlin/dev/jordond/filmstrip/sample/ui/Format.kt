package dev.jordond.filmstrip.sample.ui

import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Formats a duration the way a transport bar reads it: `1:04.2`, or `1:02:03` once it passes an
 * hour.
 *
 * The timeline's ruler labels come out of the same formatter, so a time read off the transport and
 * the same time read off the ruler are written the same way. The tick interval passed here is what
 * decides whether tenths appear.
 */
public fun Duration.asClock(): String =
  FilmstripTimelineDefaults.clockLabel(this, if (this >= 1.hours) 1.seconds else 100.milliseconds)

/**
 * Formats a byte count as the kilobytes, megabytes or gigabytes an export estimate is read in.
 */
public fun formatBytes(bytes: Long): String {
  val mb = bytes / 1_000_000.0
  return when {
    mb >= 1000 -> "${((mb / 100).roundToInt() / 10.0)} GB"
    mb < 1 -> "${(bytes / 1_000.0).roundToInt()} KB"
    else -> "${mb.roundToInt()} MB"
  }
}

/**
 * Formats a multiplier as a percentage, so `1.15f` reads as `115%`.
 */
public fun formatPercent(value: Float): String = "${(value * 100).roundToInt()}%"

/**
 * Formats an angle as whole degrees, so `137.4f` reads as `137°`.
 */
public fun formatDegrees(value: Float): String = "${value.roundToInt()}°"

/**
 * Formats a fraction of the frame as the two decimal places an overlay inset is authored in.
 */
public fun formatFraction(value: Float): String {
  val hundredths = (abs(value) * 100).roundToInt()
  val sign = if (value < 0f) "-" else ""
  return "$sign${hundredths / 100}.${(hundredths % 100).pad()}"
}

private fun Int.pad(): String = if (this < 10) "0$this" else "$this"
