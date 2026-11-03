package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.time.Duration

/**
 * How long an export has left once [fraction] of it is done after [elapsed], read as the rest running at the rate so
 * far.
 *
 * @param fraction How much of the export is done, above zero and at most one.
 * @return The time left, or null once that rate leaves none.
 */
@InternalFilmstripApi
public fun remainingAt(
  fraction: Float,
  elapsed: Duration,
): Duration? = ((elapsed / fraction.toDouble()) - elapsed).takeIf { it > Duration.ZERO }
