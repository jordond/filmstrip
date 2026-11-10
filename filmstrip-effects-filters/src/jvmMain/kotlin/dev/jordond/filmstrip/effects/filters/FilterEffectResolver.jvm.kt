package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.camcorderResolution
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.crtFragment
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.filmFragment
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.glitchFragment
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.lut.lutFragment
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.nightVisionFragment
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.pixelFragment
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhsFragment

/**
 * Resolves the effects in this module for ffmpeg filter graphs.
 */
public actual class FilterEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.FilterGraph) return null
    return when (spec) {
      is Camcorder -> {
        camcorderResolution(spec, attributes)
      }
      is Vhs -> {
        EffectResolution.Resolved(PlatformEffect(vhsFragment(spec, attributes.inputSize)))
      }
      is Film -> {
        EffectResolution.Resolved(PlatformEffect(filmFragment(spec, attributes.inputSize)))
      }
      is Crt -> {
        EffectResolution.Resolved(PlatformEffect(crtFragment(spec, attributes.inputSize)))
      }
      is Lut -> {
        EffectResolution.Resolved(PlatformEffect(lutFragment(spec)))
      }
      is NightVision -> {
        EffectResolution.Resolved(PlatformEffect(nightVisionFragment(spec, attributes.inputSize)))
      }
      is Pixel -> {
        EffectResolution.Resolved(PlatformEffect(pixelFragment(spec, attributes.inputSize)))
      }
      is Glitch -> {
        EffectResolution.Resolved(PlatformEffect(glitchFragment(spec)))
      }
      else -> {
        null
      }
    }
  }
}
