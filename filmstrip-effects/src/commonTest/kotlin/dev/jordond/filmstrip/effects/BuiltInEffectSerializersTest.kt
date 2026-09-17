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
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.motion.Easing
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// Reading builtInEffectSerializers at all is half the test. Without the serialization plugin on
// this module the property throws while it initialises.
@OptIn(ExperimentalFilmstripApi::class)
class BuiltInEffectSerializersTest {
  private val json = Json { serializersModule = builtInEffectSerializers }

  @Test
  fun roundTripsBuiltInSpecs() {
    val specs: List<EffectSpec> =
      listOf(
        Rotate(degrees = 90),
        Flip(axis = FlipAxis.Horizontal),
        Scale(targetHeight = 720),
        KenBurns(from = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f), to = NormalizedRect.Full, easing = Easing.EaseOut),
        Brightness(factor = 0.5f),
        RgbAdjustment(red = 0.9f, green = 1f, blue = 1.2f),
        Contrast(factor = 1.5f),
        Saturation(factor = 0.5f),
        HueRotate(degrees = 90f),
        Sepia(amount = 0.4f),
        Invert(amount = 1f),
        ColorMatrix(rg = 0.5f, bBias = 0.1f),
      )

    specs.forEach { spec ->
      val encoded = json.encodeToString(PolymorphicSerializer(EffectSpec::class), spec)
      assertEquals(spec, json.decodeFromString(PolymorphicSerializer(EffectSpec::class), encoded))
    }
  }

  @Test
  fun roundTripsOverlaysAsOverlayEffects() {
    val overlays: List<OverlayEffect> =
      listOf(
        ImageOverlay(ImageSource.of("/logo.png"), Corner.BottomEnd),
        TextOverlay("caption"),
      )

    overlays.forEach { overlay ->
      val encoded = json.encodeToString(PolymorphicSerializer(OverlayEffect::class), overlay)
      assertEquals(overlay, json.decodeFromString(PolymorphicSerializer(OverlayEffect::class), encoded))
    }
  }

  @Test
  fun dropsAnOverlayAnimationOnTheWayThroughJson() {
    val animated: List<OverlayEffect> =
      listOf(
        ImageOverlay(ImageSource.of("/logo.png"), Corner.BottomEnd, animation = fadeIn(1.seconds)),
        TextOverlay("caption", animation = fadeIn(1.seconds)),
      )
    val plain: List<OverlayEffect> =
      listOf(
        ImageOverlay(ImageSource.of("/logo.png"), Corner.BottomEnd),
        TextOverlay("caption"),
      )

    animated.zip(plain).forEach { (overlay, withoutAnimation) ->
      val encoded = json.encodeToString(PolymorphicSerializer(OverlayEffect::class), overlay)
      val decoded = json.decodeFromString(PolymorphicSerializer(OverlayEffect::class), encoded)

      assertNull(decoded.animation)
      assertEquals(withoutAnimation, decoded)
      assertNotEquals(overlay, decoded)
    }
  }
}
