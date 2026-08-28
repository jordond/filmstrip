package dev.jordond.filmstrip.sample.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Formats a duration the way a transport bar reads it: `1:04.2`, or `1:02:03` once it passes an
 * hour.
 */
public fun Duration.asClock(): String {
  val totalTenths = (inWholeMilliseconds / 100).coerceAtLeast(0)
  val tenths = totalTenths % 10
  val totalSeconds = totalTenths / 10
  val seconds = totalSeconds % 60
  val minutes = (totalSeconds / 60) % 60
  val hours = totalSeconds / 3600

  return if (hours > 0) {
    "$hours:${minutes.pad()}:${seconds.pad()}"
  } else {
    "$minutes:${seconds.pad()}.$tenths"
  }
}

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
 * Formats a fraction of the frame as the two decimal places an overlay inset is authored in.
 */
public fun formatFraction(value: Float): String {
  val hundredths = (abs(value) * 100).roundToInt()
  val sign = if (value < 0f) "-" else ""
  return "$sign${hundredths / 100}.${(hundredths % 100).pad()}"
}

private fun Long.pad(): String = if (this < 10) "0$this" else "$this"

private fun Int.pad(): String = if (this < 10) "0$this" else "$this"
