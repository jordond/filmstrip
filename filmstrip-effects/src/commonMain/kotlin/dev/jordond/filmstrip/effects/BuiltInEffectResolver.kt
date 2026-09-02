package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.FilmstripBuilder
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
 * Without this call a built-in effect resolves to nothing and `plan` refuses it by name.
 */
public fun FilmstripBuilder.builtInEffects(): FilmstripBuilder = addEffectResolver(BuiltInEffectResolver())
