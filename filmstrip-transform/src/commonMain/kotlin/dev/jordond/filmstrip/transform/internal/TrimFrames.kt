package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Whether a trim from [start] up to [end] holds no frame of a source running at [sourceRate].
 *
 * Reads the frame grid from the source's first frame at zero, so a trim that opens on a frame's own time holds that
 * frame. A backend whose read would come back empty for such a trim widens it, since a covered slot shows the nearest
 * frame whichever side of the trim that sits.
 */
@InternalFilmstripApi
public fun trimHoldsNoFrame(
  start: Duration,
  end: Duration,
  sourceRate: Double,
): Boolean {
  val first = ceil(start.toDouble(DurationUnit.SECONDS) * sourceRate)
  return (first / sourceRate).seconds >= end
}

/**
 * The frame rate this clip's source runs at, or [outputRate] for a container that doesn't say.
 */
@InternalFilmstripApi
public fun ResolvedClip.sourceRateOr(outputRate: Int): Double =
  info.video
    ?.frameRate
    ?.toDouble()
    ?.takeIf { it > 0 } ?: outputRate.toDouble()

/**
 * Whether this clip's trim holds no frame of its source, read at [sourceRateOr].
 */
@InternalFilmstripApi
public fun ResolvedClip.holdsNoFrame(outputRate: Int): Boolean = trimHoldsNoFrame(start, end, sourceRateOr(outputRate))
