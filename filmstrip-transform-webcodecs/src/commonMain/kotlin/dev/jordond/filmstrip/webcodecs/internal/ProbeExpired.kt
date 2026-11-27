package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.FilmstripError
import kotlin.time.Duration

/**
 * Thrown by a read of a source that was still waiting at its deadline, carrying the [error] a plan refuses with.
 */
internal class ProbeExpired(
  val error: FilmstripError.TimedOut,
) : RuntimeException(error.message)

/**
 * The error for [operation] having waited [deadline] and been stopped.
 */
internal fun probeTimedOut(
  operation: String,
  deadline: Duration,
): FilmstripError.TimedOut =
  FilmstripError.TimedOut(operation, deadline, "The $operation was still waiting after $deadline and was stopped.")
