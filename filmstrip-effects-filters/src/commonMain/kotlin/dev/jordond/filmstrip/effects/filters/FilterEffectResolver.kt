package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.FilmstripRegistrar
import dev.jordond.filmstrip.RegistrarId
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
 * The catalogue registers itself when this artifact is a dependency, so a builder only calls this with discovery
 * turned off, or to put a resolver of its own behind the catalogue. A second call on the same builder does nothing.
 */
public fun FilmstripBuilder.filters(): FilmstripBuilder = install(FiltersRegistrar())

internal class FiltersRegistrar : FilmstripRegistrar {
  override val id: RegistrarId = RegistrarId("filmstrip-effects-filters")

  override fun register(builder: FilmstripBuilder) {
    builder.addEffectResolver(FilterEffectResolver()).addAudioEffectResolver(FilterAudioEffectResolver())
  }
}
