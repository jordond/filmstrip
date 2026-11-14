package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

/**
 * A [glitch] as one browser pass running [GlitchShader], with its uniforms read off the frame's composition time and
 * size.
 *
 * Every texture the compositor hands a pass runs up the picture and clamps to its edge, which the shifted reads rely
 * on. A clip's frame holds clear bars, so alpha moves with green.
 */
internal fun glitchPass(glitch: Glitch): WebGlPass =
  WebGlPass(
    Glitch.ID.value,
    listOf(
      WebGlStep(
        name = "tear",
        fragmentSource = GlitchShader.FRAGMENT,
        reads = mapOf(GlitchShader.TEXTURE to WebGlStep.INPUT),
      ) { time, width, height ->
        buildMap {
          GlitchShader.uniforms(glitch, time.toDouble(DurationUnit.SECONDS)).forEach { (name, value) ->
            put(name, floatArrayOf(value))
          }
          put(GlitchShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
          put(GlitchShader.FLIP_V, floatArrayOf(1f))
        }
      },
    ),
  )
