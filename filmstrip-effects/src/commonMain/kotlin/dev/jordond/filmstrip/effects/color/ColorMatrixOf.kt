package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec

/**
 * The matrix behind [spec], or null for an effect that is not a color matrix.
 *
 * This is the one place the catalogue's color effects are turned into numbers, so every backend
 * lowers the same matrix and the fold that merges a run of them sees the same one too.
 *
 * @return the matrix, or null for an effect that is not a color matrix.
 */
@InternalFilmstripApi
public fun colorMatrixOf(spec: EffectSpec): ColorMatrix? =
  when (spec) {
    is Brightness -> spec.matrix
    is RgbAdjustment -> spec.matrix
    is Contrast -> spec.matrix
    is Saturation -> spec.matrix
    is HueRotate -> spec.matrix
    is Sepia -> spec.matrix
    is Invert -> spec.matrix
    is ColorMatrix -> spec
    else -> null
  }
