package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderEffect
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtEffect
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmEffect
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.GlitchEffect
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.lut.LutEffect
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.NightVisionEffect
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelEffect
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsEffect

/**
 * Resolves the effects in this module for media3.
 */
public actual class FilterEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? =
    when (spec) {
      is Camcorder -> {
        EffectResolution.Resolved(PlatformEffect(CamcorderEffect(CamcorderOsd(spec, attributes.frameRate))))
      }
      is Vhs -> {
        EffectResolution.Resolved(PlatformEffect(VhsEffect(spec, attributes.previewClock)))
      }
      is Film -> {
        EffectResolution.Resolved(PlatformEffect(FilmEffect(spec)))
      }
      is Crt -> {
        EffectResolution.Resolved(PlatformEffect(CrtEffect(spec)))
      }
      is Lut -> {
        EffectResolution.Resolved(PlatformEffect(LutEffect(spec)))
      }
      is NightVision -> {
        EffectResolution.Resolved(PlatformEffect(NightVisionEffect(spec)))
      }
      is Pixel -> {
        EffectResolution.Resolved(PlatformEffect(PixelEffect(spec)))
      }
      is Glitch -> {
        EffectResolution.Resolved(PlatformEffect(GlitchEffect(spec)))
      }
      else -> {
        null
      }
    }
}
