package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

/**
 * A [vhs] as one browser pass running [VhsShader], with its uniforms read off the frame's composition time and size.
 *
 * Every texture the compositor hands a pass runs up the picture. A clip's frame holds clear bars, so alpha moves with
 * the displacement, and a composition's frame is opaque everywhere, so moving it changes nothing there.
 */
internal fun vhsPass(vhs: Vhs): WebGlPass =
  WebGlPass(
    Vhs.ID,
    listOf(
      WebGlStep(
        name = "tape",
        fragmentSource = VhsShader.FRAGMENT,
        reads = mapOf(VhsShader.TEXTURE to WebGlStep.INPUT),
      ) { time, width, height ->
        buildMap {
          VhsShader.uniforms(vhs, time.toDouble(DurationUnit.SECONDS)).forEach { (name, value) ->
            put(name, floatArrayOf(value))
          }
          put(VhsShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
          put(VhsShader.FLIP_V, floatArrayOf(1f))
        }
      },
    ),
  )
