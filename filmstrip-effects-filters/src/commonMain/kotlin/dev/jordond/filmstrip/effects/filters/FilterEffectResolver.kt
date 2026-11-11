package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities

/**
 * Resolves the effects in this module for the current platform.
 */
public expect class FilterEffectResolver() : EffectResolver {
  /**
   * Realises [spec] for the current platform.
   *
   * @return The resolved effect, or null to decline [spec] and leave it to the next resolver.
   */
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution?
}

/**
 * Registers the filter catalogue, the looks and the sounds.
 *
 * Without this call a filter resolves to nothing and `plan` refuses it by name.
 */
public fun FilmstripBuilder.filters(): FilmstripBuilder =
  addEffectResolver(FilterEffectResolver()).addAudioEffectResolver(FilterAudioEffectResolver())
