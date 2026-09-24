package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMTimeSubtract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The timescale every [CMTime] this backend builds is expressed in.
 *
 * 3600 divides evenly by 24, 25, 30, 48, 50, 60, 72, 90, 100, 120, 144, 150, 180, 200, 240 and 300,
 * so a frame boundary at any of those rates lands on a whole tick instead of being rounded onto one.
 * A rate it does not divide, such as 32 or 96, lands each boundary on its nearest tick, up to half a
 * tick from the frame itself. The clip ending there and the clip opening there round the same
 * boundary, so they still meet. Mixing timescales inside one composition is what makes clip spans
 * fail to tile, so there is exactly one.
 *
 * The video composition's frame duration is the one time built at another timescale. It is the
 * cadence frames are rendered at rather than a range laid on a track, and it is one frame at the
 * output's own rate.
 */
internal const val MEDIA_TIMESCALE: Int = 3600

/**
 * One tick at [MEDIA_TIMESCALE], the shortest span this backend can name.
 */
internal val MEDIA_TICK: Duration = (1.0 / MEDIA_TIMESCALE).seconds

/**
 * This duration as a [CMTime] at [MEDIA_TIMESCALE].
 *
 * Converted through microseconds. A `Double` of seconds cannot hold a tick exactly at the far end
 * of a long composition, and a tick that lands half a unit out is a span boundary that does not
 * meet its neighbour.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun Duration.toCMTime(): CValue<CMTime> = CMTimeMake(value = ticks(), timescale = MEDIA_TIMESCALE)

/**
 * This [CMTime] as a [Duration], with an invalid or negative time reading as zero.
 *
 * `CMTimeGetSeconds` answers `NaN` for the invalid and indefinite times AVFoundation hands back
 * from an asset it could not load, and every caller here wants a real number.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun CValue<CMTime>.toDuration(): Duration {
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
 * How long the stretch from [start] until [end] runs once each end is on its own tick at
 * [MEDIA_TIMESCALE].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ticksBetween(
  start: Duration,
  end: Duration,
): CValue<CMTime> = CMTimeSubtract(end.toCMTime(), start.toCMTime())

/**
 * Whole ticks at [MEDIA_TIMESCALE], rounded to nearest so a duration that falls between two lands
 * on the closer one.
 */
private fun Duration.ticks(): Long {
  val micros = inWholeMicroseconds
  return (micros * MEDIA_TIMESCALE + MICROS_PER_SECOND / 2) / MICROS_PER_SECOND
}

private const val MICROS_PER_SECOND = 1_000_000L
