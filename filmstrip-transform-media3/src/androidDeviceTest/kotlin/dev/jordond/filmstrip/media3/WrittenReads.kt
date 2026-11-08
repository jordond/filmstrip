package dev.jordond.filmstrip.media3

/**
 * How far a frame [RenderedFrames] draws may land from the shared signal, in 8-bit codes.
 *
 * The chain's output is read back as 8-bit RGBA with no encoder in the way, so this is the read's own rounding.
 */
internal const val RENDER_CODES = 2.0

/**
 * How far a colour read off a file media3 wrote may land from what it should read, in 8-bit codes.
 *
 * Measured over every encoded read in `AndroidFilmMediaTest`, twice each on the API 30 and API 36 x86_64 emulators, and
 * rounded up to the next whole code. The largest was the film glow on API 36, 4.41 codes off the signal where the
 * chain drew it 1.41 off, and API 30 read it 2.41 off.
 */
internal const val WRITTEN_COLOUR_CODES = 5.0
