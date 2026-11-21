package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.filters.internal.toWebGlSteps

/**
 * A [bloom] as one browser pass running [BloomShader]'s chain, with its uniforms read off the frame's size.
 */
internal fun bloomPass(bloom: Bloom): WebGlPass = WebGlPass(Bloom.ID.value, BloomShader.steps(bloom).toWebGlSteps())
