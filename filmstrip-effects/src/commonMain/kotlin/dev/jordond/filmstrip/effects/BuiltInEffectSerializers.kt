package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.RgbAdjustment
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.Sepia
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
 * registered polymorphically. Third-party effects contribute their own module the same way.
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
      subclass(RgbAdjustment::class)
      subclass(Contrast::class)
      subclass(Saturation::class)
      subclass(HueRotate::class)
      subclass(Sepia::class)
      subclass(Invert::class)
      subclass(ColorMatrix::class)
      subclass(ImageOverlay::class)
      subclass(TextOverlay::class)
    }
    polymorphic(OverlayEffect::class) {
      subclass(ImageOverlay::class)
      subclass(TextOverlay::class)
    }
  }
