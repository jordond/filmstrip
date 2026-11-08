package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * What a filter graph writes where the composition time a preview window opens at belongs, in seconds.
 *
 * A preview only knows that time per scrub, so the backend swaps this token for it before the graph runs, and for
 * zero on an export or on a graph no window opens part way into. An effect writes it into an expression that has to
 * read composition time on a pad whose clock restarts where the window opens. It is spelled with characters no
 * filter graph escapes, so it survives into the rendered text as written.
 */
@InternalFilmstripApi
public const val WINDOW_CLOCK: String = "<window-clock>"
