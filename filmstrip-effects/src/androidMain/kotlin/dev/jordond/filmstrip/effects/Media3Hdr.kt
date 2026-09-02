package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The peak media3 normalizes an HDR frame against, in cd/m2.
 *
 * media3's own transfer shader divides a PQ frame by this after decoding it against PQ's ten
 * thousand, and leaves an HLG frame alone, so one in an effect's texture is this many nits either
 * way. This is media3's figure rather than the format's.
 */
@InternalFilmstripApi
public const val MEDIA3_HDR_PEAK_NITS: Float = 1_000f
