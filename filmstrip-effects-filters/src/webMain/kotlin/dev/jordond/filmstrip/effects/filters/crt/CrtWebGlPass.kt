package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep

/**
 * A [crt] as one browser pass running [CrtShader], with its uniforms read off the frame's size.
 *
 * Every frame the compositor hands a pass clamps to its edge and filters linearly, which the curve's reads rely on.
 */
internal fun crtPass(crt: Crt): WebGlPass =
  WebGlPass(
    Crt.ID,
    listOf(
      WebGlStep(
        name = "tube",
        fragmentSource = CrtShader.FRAGMENT,
        reads = mapOf(CrtShader.TEXTURE to WebGlStep.INPUT),
      ) { _, width, height -> CrtShader.uniforms(crt, width, height) },
    ),
  )
