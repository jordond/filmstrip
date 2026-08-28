package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.RenderCapabilities
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Resolves filmstrip's built-in effects for the current platform.
 *
 * @param context The platform context. Android reads a `content://` watermark through it. Every
 *   other platform ignores it.
 */
public expect class BuiltInEffectResolver(
  context: PlatformContext,
) : EffectResolver {
  /**
   * Realises [spec] for the current platform.
   *
   * @return The resolved effect, or null to decline [spec] and leave it to the next resolver.
   */
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution?
}

/**
 * Registers the built-in effect catalogue.
 *
 * Without this call a built-in effect resolves to nothing and `plan` refuses it by name.
 */
public fun FilmstripBuilder.builtInEffects(): FilmstripBuilder = addEffectResolver(BuiltInEffectResolver(context))

/**
 * Serialisers for the built-in effect catalogue.
 *
 * [EffectSpec] is an open interface, so persisting a composition needs every implementation
 * registered polymorphically. Third-party effects contribute their own module the same way.
 */
public val builtInEffectSerializers: SerializersModule =
  SerializersModule {
    polymorphic(EffectSpec::class) {
      subclass(Rotate::class)
      subclass(Flip::class)
      subclass(Crop::class)
      subclass(CropRect::class)
      subclass(Scale::class)
      subclass(Brightness::class)
      subclass(Watermark::class)
      subclass(Text::class)
    }
  }
