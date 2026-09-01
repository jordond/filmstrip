package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.ExportStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Turns the `-progress pipe:1` key stream into [ExportStatus.Progress].
 *
 * One block per report, terminated by `progress=continue` or `progress=end`. Values arrive
 * space-padded, several read `N/A` on the first block, and `speed` carries a trailing `x`.
 *
 * @param totalMicros How long the output runs, from the plan. Progress is a fraction of what was
 *   planned rather than of what has been seen, because the second is not knowable in advance.
 */
internal class ProgressParser(
  private val totalMicros: Long,
) {
  private var outTimeMicros: Long? = null
  private var speed: Double? = null
  private var highestFraction = 0f

  /**
   * Feeds one line.
   *
   * @return A status when the line closed a block, null otherwise.
   */
  fun accept(line: String): ExportStatus.Progress? {
    val key = line.substringBefore('=', missingDelimiterValue = "").trim()
    val value = line.substringAfter('=', missingDelimiterValue = "").trim()
    if (key.isEmpty()) return null

    when (key) {
      // Both keys print from the same AV_TIME_BASE variable, so `out_time_ms` is microseconds
      // despite its name. A 6.000 second encode reports out_time_ms=6000000.
      "out_time_us" -> outTimeMicros = value.toLongOrNull()
      "speed" -> speed = value.removeSuffix("x").toDoubleOrNull()
      "progress" -> return snapshot(ended = value == END)
    }
    return null
  }

  private fun snapshot(ended: Boolean): ExportStatus.Progress {
    val position = outTimeMicros
    val fraction =
      when {
        // `progress=end` closes the last block ffmpeg writes, so everything the plan asked for is
        // encoded. out_time lands a frame or so short of the planned duration on some builds, and
        // reading that back as a fraction reports an export that finished as unfinished.
        ended -> 1f
        totalMicros <= 0L || position == null -> highestFraction
        else -> (position.toFloat() / totalMicros.toFloat()).coerceIn(0f, 1f)
      }
    // The KDoc promises a fraction that never goes backwards, and out_time does go backwards
    // between the first blocks of some encodes.
    highestFraction = maxOf(highestFraction, fraction)

    return ExportStatus.Progress(
      fraction = highestFraction,
      position = position?.microseconds,
      // speed is a measured ratio of media time to wall time, so this is a measurement rather than
      // an extrapolation from a percentage. It is absent on the first block, which is exactly what
      // the nullable is documented to mean.
      estimatedRemaining = remaining(position),
    )
  }

  private fun remaining(position: Long?): Duration? {
    val rate = speed?.takeIf { it > 0.0 } ?: return null
    val done = position ?: return null
    if (totalMicros <= 0L) return null
    val left = (totalMicros - done).coerceAtLeast(0L)
    return (left / rate).microseconds
  }

  private companion object {
    const val END = "end"
  }
}
