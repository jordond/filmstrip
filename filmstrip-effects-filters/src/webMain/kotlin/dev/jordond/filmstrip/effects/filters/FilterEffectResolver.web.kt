package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.camcorderPass
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.crtPass
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.filmPass
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.glitchPass
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.lut.lutPass
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.nightVisionPass
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.pixelPass
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhsPass

/**
 * Resolves the effects in this module for the browser.
 */
public actual class FilterEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? =
    when (spec) {
      is Camcorder -> {
        EffectResolution.Resolved(PlatformEffect(camcorderPass(spec, attributes.frameRate)))
      }
      is Vhs -> {
        EffectResolution.Resolved(PlatformEffect(vhsPass(spec, attributes.previewClock)))
      }
      is Film -> {
        EffectResolution.Resolved(PlatformEffect(filmPass(spec)))
      }
      is Crt -> {
        EffectResolution.Resolved(PlatformEffect(crtPass(spec)))
      }
      is Lut -> {
        EffectResolution.Resolved(PlatformEffect(lutPass(spec)))
      }
      is NightVision -> {
        EffectResolution.Resolved(PlatformEffect(nightVisionPass(spec)))
      }
      is Pixel -> {
        EffectResolution.Resolved(PlatformEffect(pixelPass(spec)))
      }
      is Glitch -> {
        EffectResolution.Resolved(PlatformEffect(glitchPass(spec)))
      }
      else -> {
        null
      }
    }
}
