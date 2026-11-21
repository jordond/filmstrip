package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.filters.internal.toWebGlSteps

/**
 * A [digicam] as one browser pass running [DigicamShader]'s chain, with its uniforms read off the frame's time and
 * size.
 */
internal fun digicamPass(digicam: Digicam): WebGlPass =
  WebGlPass(
    Digicam.ID.value,
    DigicamShader.steps(digicam).toWebGlSteps(mapOf(DigicamShader.FLIP_V to floatArrayOf(1f))),
  )
