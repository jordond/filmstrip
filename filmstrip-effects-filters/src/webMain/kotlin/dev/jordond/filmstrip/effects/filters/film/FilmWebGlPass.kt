package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.filters.internal.toWebGlSteps

/**
 * A [film] as one browser pass running [FilmShader]'s chain, with its uniforms read off the frame's composition time
 * and size.
 *
 * Every texture the compositor hands a pass runs up the picture.
 */
internal fun filmPass(film: Film): WebGlPass =
  WebGlPass(Film.ID.value, FilmShader.steps(film).toWebGlSteps(mapOf(FilmShader.FLIP_V to floatArrayOf(1f))))
