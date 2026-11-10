package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.camcorderEffect
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.crtKernelLimit
import dev.jordond.filmstrip.effects.filters.crt.crtResolution
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.filmKernelLimit
import dev.jordond.filmstrip.effects.filters.film.filmResolution
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.glitchKernelLimit
import dev.jordond.filmstrip.effects.filters.glitch.glitchResolution
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.lut.lutKernelLimit
import dev.jordond.filmstrip.effects.filters.lut.lutResolution
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.nightVisionKernelLimit
import dev.jordond.filmstrip.effects.filters.nightvision.nightVisionResolution
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.pixelKernelLimit
import dev.jordond.filmstrip.effects.filters.pixel.pixelResolution
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhsKernelLimit
import dev.jordond.filmstrip.effects.filters.vhs.vhsResolution

/**
 * Resolves the effects in this module for Core Image.
 */
public actual class FilterEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.CoreImage && capabilities.api != RenderApi.Metal) return null

    return when (spec) {
      is Camcorder -> {
        EffectResolution.Resolved(camcorderEffect(spec, attributes.frameRate))
      }
      is Vhs -> {
        vhsResolution(spec, vhsKernelLimit())
      }
      is Film -> {
        filmResolution(spec, filmKernelLimit())
      }
      is Crt -> {
        crtResolution(spec, crtKernelLimit())
      }
      is Lut -> {
        lutResolution(spec, lutKernelLimit())
      }
      is NightVision -> {
        nightVisionResolution(spec, nightVisionKernelLimit())
      }
      is Pixel -> {
        pixelResolution(spec, pixelKernelLimit())
      }
      is Glitch -> {
        glitchResolution(spec, glitchKernelLimit())
      }
      else -> {
        null
      }
    }
  }
}
