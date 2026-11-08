package dev.jordond.filmstrip.ffmpeg.internal.process

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a short child reading [window] of a source may run before it is stopped.
 *
 * Every child gets [PROBE_DEADLINE_BASE] for starting up and opening the source, and a read that grows with the window
 * it covers gets [PROBE_DEADLINE_WINDOW_FRACTION] of that window on top. A child that reads no window passes zero. An
 * export or a preview runs as long as its media does and has no deadline.
 */
internal fun probeDeadline(window: Duration = Duration.ZERO): Duration =
  PROBE_DEADLINE_BASE + window.coerceAtLeast(Duration.ZERO) * PROBE_DEADLINE_WINDOW_FRACTION

/**
 * Why a read was refused when its child was still running at [deadline].
 */
internal fun probeExpiredReason(
  tool: String,
  deadline: Duration,
): String = "`$tool` was still running after $deadline and was stopped before it finished reading."

/**
 * What a read through a short child came back with.
 *
 * [Answered] holds what a finished read found, and [Expired] holds why a child stopped at its deadline was stopped.
 */
internal sealed interface ProbeAnswer<out T> {
  class Answered<T>(
    val value: T,
  ) : ProbeAnswer<T>

  class Expired(
    val reason: String,
  ) : ProbeAnswer<Nothing>
}

// Long enough for a large picture or a slow disk on a loaded machine, and short of leaving a plan hung on a child
// that never finishes.
internal val PROBE_DEADLINE_BASE: Duration = 30.seconds

/**
 * The share of a read's window added to [PROBE_DEADLINE_BASE].
 *
 * On a Linux build box, ffprobe 8.0.1 listed the packets of an hour of 720p30 H.264 in 0.49 s and of a ten minute
 * window in the middle of it in 0.13 s, about 0.06 s of which is starting up. That is roughly 0.12 ms for each second
 * of window, and this allows 10 ms, so a two hour copy's packet read gets 72 s past the base.
 */
internal const val PROBE_DEADLINE_WINDOW_FRACTION: Double = 0.01
