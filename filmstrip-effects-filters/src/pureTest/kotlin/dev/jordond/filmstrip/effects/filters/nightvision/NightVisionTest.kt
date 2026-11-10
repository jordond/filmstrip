package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.color.VIGNETTE_GAIN_GLSL
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

/**
 * What a [NightVision] declares to the planner, the range it holds, and what its shader carries from the signal.
 */
class NightVisionTest {
  @Test
  fun declaresItsPlaceBeneathOverlaysAndThatItNeedsSdr() {
    val nightVision = NightVision()

    nightVision.stage shouldBe EffectStage.Composite
    nightVision.beneathOverlays shouldBe true
    nightVision.requiresSdr shouldBe true
  }

  @Test
  fun isInertOnlyAtIntensityZero() {
    NightVision(intensity = 0f).isInert shouldBe true
    NightVision(intensity = 0.5f).isInert shouldBe false
  }

  @Test
  fun refusesAnIntensityOutsideItsRange() {
    shouldThrow<IllegalArgumentException> { NightVision(intensity = -0.1f) }
    shouldThrow<IllegalArgumentException> { NightVision(intensity = 1.5f) }
  }

  @Test
  fun theBuilderAddsTheLookItWasGiven() {
    val builder = EffectsBuilder()

    builder.nightVision(intensity = 0.5f, seed = 7)

    builder.build() shouldContainExactly listOf(NightVision(0.5f, 7))
  }

  @Test
  fun theFragmentOpensOnItsVersionAndCarriesTheRamp() {
    val fragment = NightVisionShader.FRAGMENT

    fragment shouldStartWith "#version 300 es"
    fragment shouldContain RAMP_MID_COLOUR[1].toString()
    fragment shouldContain RAMP_TOP_COLOUR[2].toString()
    fragment shouldContain VIGNETTE_GAIN_GLSL
    fragment shouldContain
      "${glslFloat(NIGHT_VISION_VIGNETTE_STRENGTH)}, ${glslFloat(NIGHT_VISION_VIGNETTE_RADIUS)}, " +
      "${glslFloat(NIGHT_VISION_VIGNETTE_SOFTNESS)});"
  }

  @Test
  fun theUniformsCarryTheClockAndTheFrame() {
    val uniforms = NightVisionShader.uniforms(NightVision(0.5f, 42), 2.5, WIDTH, HEIGHT)

    uniforms.getValue("uTick")[0] shouldBe FilmSignal.grainTickAt(2.5).toFloat()
    uniforms.getValue("uCellSpan")[0] shouldBe FilmSignal.grainCellSpan(WIDTH, HEIGHT).toFloat()
    uniforms.getValue("uSeed")[0] shouldBe 42f
    uniforms.getValue("uIntensity")[0] shouldBe 0.5f
  }

  // media3 only registers a uniform the fragment holds, and setting any other fails the first frame.
  @Test
  fun theUniformsAreTheOnesTheFragmentDeclares() {
    val declared =
      Regex("""uniform (?:float|vec2) ([^;]+);""")
        .findAll(NightVisionShader.FRAGMENT)
        .flatMap { match -> match.groupValues[1].split(",").map(String::trim) }
        .toSet() - NightVisionShader.FLIP_V

    NightVisionShader.uniforms(NightVision(), 0.0, WIDTH, HEIGHT).keys shouldBe declared
  }
}

private const val WIDTH = 1366
private const val HEIGHT = 768
