package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SpatialSpecTest {
  @Test
  fun `a blur runs at the spatial stage and is inert at zero alone`() {
    GaussianBlur(0.05f).stage shouldBe EffectStage.Spatial
    GaussianBlur(0f).isInert.shouldBeTrue()
    GaussianBlur(0.05f).isInert.shouldBeFalse()
  }

  @Test
  fun `a blur takes a sigma from zero to a tenth of the width`() {
    GaussianBlur(0.1f).sigma shouldBe 0.1f
    shouldThrow<IllegalArgumentException> { GaussianBlur(-0.01f) }
    shouldThrow<IllegalArgumentException> { GaussianBlur(0.11f) }
    shouldThrow<IllegalArgumentException> { GaussianBlur(Float.NaN) }
  }

  @Test
  fun `a vignette runs at the spatial stage and is inert at zero strength alone`() {
    Vignette().stage shouldBe EffectStage.Spatial
    Vignette(strength = 0f).isInert.shouldBeTrue()
    Vignette(strength = 0.5f, radius = 0f, softness = 0f).isInert.shouldBeFalse()
  }

  @Test
  fun `a vignette holds each value to its range`() {
    Vignette(strength = 1f, radius = 1.5f, softness = 1f).radius shouldBe 1.5f
    shouldThrow<IllegalArgumentException> { Vignette(strength = 1.01f) }
    shouldThrow<IllegalArgumentException> { Vignette(radius = 1.51f) }
    shouldThrow<IllegalArgumentException> { Vignette(softness = -0.01f) }
    shouldThrow<IllegalArgumentException> { Vignette(strength = Float.NaN) }
  }
}
