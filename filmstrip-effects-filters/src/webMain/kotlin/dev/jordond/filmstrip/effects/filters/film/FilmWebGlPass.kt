package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

/**
 * A [film] as one browser pass running [FilmShader]'s chain, with its uniforms read off the frame's composition time
 * and size.
 *
 * Every texture the compositor hands a pass runs up the picture.
 */
internal fun filmPass(film: Film): WebGlPass =
  WebGlPass(
    Film.ID,
    FilmShader.steps(film).map { step ->
      WebGlStep(
        name = step.name,
        fragmentSource = step.fragment,
        reads = step.reads.mapValues { (_, source) -> if (source == FilmShader.INPUT) WebGlStep.INPUT else source },
        targetAt = step.targetAt,
      ) { time, width, height ->
        step.uniformsAt(time.toDouble(DurationUnit.SECONDS), width, height) + (FilmShader.FLIP_V to floatArrayOf(1f))
      }
    },
  )
