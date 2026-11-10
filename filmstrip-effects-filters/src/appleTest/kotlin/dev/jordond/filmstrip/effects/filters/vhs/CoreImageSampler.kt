package dev.jordond.filmstrip.effects.filters.vhs

/**
 * How fine Core Image's sampler weighs the two texels a read lands between, which is Core Image's limit and not the
 * signal's. It holds the weight as an 8 bit fraction, so a read between two texels lands up to one step of that
 * fraction off the signal's weight, times how far apart the texels are.
 */
internal const val SAMPLER_WEIGHT_STEP = 1.0 / 256
