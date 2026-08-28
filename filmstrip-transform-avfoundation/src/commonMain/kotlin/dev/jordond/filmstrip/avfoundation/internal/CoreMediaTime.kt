package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The timescale every [CMTime] this backend builds is expressed in.
 *
 * 600 divides evenly by 24, 25, 30 and 60, so a frame boundary at any rate filmstrip encodes lands
 * on a whole tick instead of being rounded onto one. Mixing timescales inside one composition is
 * what makes clip spans fail to tile, so there is exactly one.
 */
internal const val MEDIA_TIMESCALE: Int = 600

/**
 * This duration as a [CMTime] at [MEDIA_TIMESCALE].
 *
 * Converted through microseconds. A `Double` of seconds cannot hold a tick exactly at the far end
 * of a long composition, and a tick that lands half a unit out is a span boundary that does not
 * meet its neighbour.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun Duration.toCMTime(): CValue<CMTime> = CMTimeMake(value = ticks(), timescale = MEDIA_TIMESCALE)

/**
 * This [CMTime] as a [Duration], with an invalid or negative time reading as zero.
 *
 * `CMTimeGetSeconds` answers `NaN` for the invalid and indefinite times AVFoundation hands back
 * from an asset it could not load, and every caller here wants a real number.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CValue<CMTime>.toDuration(): Duration {
  val seconds = CMTimeGetSeconds(this)
  return if (seconds.isNaN() || seconds <= 0.0) Duration.ZERO else seconds.seconds
}

/**
 * The range running from [start] for [duration], at [MEDIA_TIMESCALE].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun timeRangeOf(
  start: Duration,
  duration: Duration,
): CValue<CMTimeRange> = CMTimeRangeMake(start = start.toCMTime(), duration = duration.toCMTime())

/**
 * Whole ticks at [MEDIA_TIMESCALE], rounded to nearest so a duration that falls between two lands
 * on the closer one.
 */
private fun Duration.ticks(): Long {
  val micros = inWholeMicroseconds
  return (micros * MEDIA_TIMESCALE + MICROS_PER_SECOND / 2) / MICROS_PER_SECOND
}

private const val MICROS_PER_SECOND = 1_000_000L
