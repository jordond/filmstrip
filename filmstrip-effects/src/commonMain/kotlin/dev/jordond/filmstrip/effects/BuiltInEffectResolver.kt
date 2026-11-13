package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.FilmstripRegistrar
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities

/**
 * Resolves filmstrip's built-in effects for the current platform.
 */
public expect class BuiltInEffectResolver() : EffectResolver {
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
 * Registers the built-in effect catalogue.
 *
 * Every backend and the player register it themselves, so a builder only calls this to put a resolver of its own
 * behind the catalogue. A second call on the same builder does nothing.
 */
public fun FilmstripBuilder.builtInEffects(): FilmstripBuilder = install(BuiltInEffectsRegistrar())

internal class BuiltInEffectsRegistrar : FilmstripRegistrar {
  override val id: String = "filmstrip-effects"

  override fun register(builder: FilmstripBuilder) {
    builder.addEffectResolver(BuiltInEffectResolver())
  }
}
