package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The seam between `filmstrip-core` and a module that knows how to read a container.
 *
 * Not a published extension point. It is how core reaches a capability another artifact owns.
 * Core keeps a prober of its own and consults it last, so registering one is an override rather
 * than the only answer.
 */
@InternalFilmstripApi
public fun interface MediaProber {
  /**
   * Reads [source]'s metadata without decoding it.
   *
   * @param source The media to read.
   * @return What the source is, or why this prober could not read it.
   */
  public suspend fun probe(source: MediaSource): ProbeResult
}

/**
 * Builds a [MediaProber], or declines.
 */
@InternalFilmstripApi
public fun interface MediaProberFactory {
  /**
   * Builds a prober.
   *
   * @return A prober, or null to defer to the next factory.
   */
  public fun create(): MediaProber?
}
