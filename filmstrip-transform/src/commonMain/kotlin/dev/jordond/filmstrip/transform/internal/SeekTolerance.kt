package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.time.Duration

// How far off the time it was asked for a served frame may land, derived once. Every backend seeks
// differently and each one is free to land closer than this, but none of them may land further, and
// a shared bound is something a backend's tests can be held to where a sentence is not.

/**
 * How far the frame a source served may sit from the time that was asked for.
 *
 * A precise request decodes forward to the frame covering that time, so it lands within one
 * [frameStep] of it. A request that will take the nearest sync sample lands on whichever side of
 * the requested time that sample falls, which is an interval away at worst, plus the step for a
 * sample that does not sit on the requested frame's boundary.
 *
 * @param precise Whether the request asked for the frame covering the time rather than the nearest
 *   sync sample.
 * @param frameStep How long one frame of the source runs for.
 * @param syncInterval How long the source's group of pictures runs for.
 */
@InternalFilmstripApi
public fun seekTolerance(
  precise: Boolean,
  frameStep: Duration,
  syncInterval: Duration,
): Duration = if (precise) frameStep else syncInterval + frameStep
