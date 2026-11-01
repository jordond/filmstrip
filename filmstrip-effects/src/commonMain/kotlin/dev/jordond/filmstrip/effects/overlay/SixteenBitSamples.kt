package dev.jordond.filmstrip.effects.overlay

/**
 * The bits of a sixteen-bit sample read out of a [Short], which Kotlin holds signed.
 *
 * Masking a widened [Short] with it gives the sample from 0 to 65535.
 */
internal const val SAMPLE_MASK: Int = 0xFFFF

/**
 * How far apart two eight-bit codes sit once widened to sixteen bits.
 *
 * An eight-bit code times this is the sixteen-bit sample holding the same value, so 255 lands on 65535.
 */
internal const val EIGHT_BIT_STEP: Int = 257

/**
 * The highest sixteen-bit sample, which holds full intensity.
 *
 * A level from 0 to 1 times this, rounded, is the sample that holds it.
 */
internal const val TOP_SAMPLE: Int = 0xFFFF

/**
 * The highest eight-bit code, which holds full intensity.
 *
 * A code over this is the level from 0 to 1 it holds.
 */
internal const val TOP_CODE: Int = 0xFF
