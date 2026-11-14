package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

/**
 * A [nightVision] as one browser pass running [NightVisionShader], with its uniforms read off the frame's composition
 * time and size.
 *
 * Every texture the compositor hands a pass runs up the picture, so the shader counts rows down from the other end.
 * The fragment source never changes, so one program key serves every [NightVision].
 */
internal fun nightVisionPass(nightVision: NightVision): WebGlPass =
  WebGlPass(
    NightVision.ID.value,
    listOf(
      WebGlStep(
        name = "phosphor",
        fragmentSource = NightVisionShader.FRAGMENT,
        reads = mapOf(NightVisionShader.TEXTURE to WebGlStep.INPUT),
      ) { time, width, height ->
        NightVisionShader.uniforms(nightVision, time.toDouble(DurationUnit.SECONDS), width, height) +
          (NightVisionShader.FLIP_V to floatArrayOf(1f))
      },
    ),
  )
