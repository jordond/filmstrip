package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Serializers for the built-in effect catalogue.
 *
 * [EffectSpec] is an open interface, so persisting a composition needs every implementation
 * registered polymorphically. Third-party effects contribute their own module the same way. The
 * overlays are registered under [OverlayEffect] as well, so a field declared as one round-trips.
 */
@OptIn(ExperimentalFilmstripApi::class)
public val builtInEffectSerializers: SerializersModule =
  SerializersModule {
    polymorphic(EffectSpec::class) {
      subclass(Rotate::class)
      subclass(Flip::class)
      subclass(Crop::class)
      subclass(CropRect::class)
      subclass(KenBurns::class)
      subclass(Scale::class)
      subclass(Brightness::class)
      subclass(ImageOverlay::class)
      subclass(TextOverlay::class)
    }
    polymorphic(OverlayEffect::class) {
      subclass(ImageOverlay::class)
      subclass(TextOverlay::class)
    }
  }
