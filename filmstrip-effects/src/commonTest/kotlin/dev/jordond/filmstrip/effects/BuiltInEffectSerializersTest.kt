package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.FlipAxis
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// Reading builtInEffectSerializers at all is half the test. Without the serialization plugin on
// this module the property throws while it initialises, and because Kotlin initialises a file's
// top-level properties together, that takes builtInEffects() and therefore every export backend
// registration down with it.
class BuiltInEffectSerializersTest {
  private val json = Json { serializersModule = builtInEffectSerializers }

  @Test
  fun roundTripsBuiltInSpecs() {
    val specs: List<EffectSpec> =
      listOf(
        Rotate(degrees = 90),
        Flip(axis = FlipAxis.Horizontal),
        Scale(targetHeight = 720),
        Brightness(factor = 0.5f),
      )

    specs.forEach { spec ->
      val encoded = json.encodeToString(PolymorphicSerializer(EffectSpec::class), spec)
      assertEquals(spec, json.decodeFromString(PolymorphicSerializer(EffectSpec::class), encoded))
    }
  }
}
