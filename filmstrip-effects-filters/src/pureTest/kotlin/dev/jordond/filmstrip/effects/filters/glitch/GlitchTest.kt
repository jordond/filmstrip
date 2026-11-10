package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.filters.internal.uniformFloat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

/**
 * What a [Glitch] declares to the planner, and what its shader carries from the signal.
 */
class GlitchTest {
  @Test
  fun declaresItsPlaceBeneathOverlaysAndThatItNeedsSdr() {
    val glitch = Glitch()

    glitch.stage shouldBe EffectStage.Composite
    glitch.beneathOverlays shouldBe true
    glitch.requiresSdr shouldBe true
    glitch.seed shouldBe Glitch.DEFAULT_SEED
  }

  @Test
  fun isInertOnlyAtZero() {
    Glitch(0f).isInert shouldBe true
    Glitch(0.01f).isInert shouldBe false
    Glitch().isInert shouldBe false
  }

  @Test
  fun comparesByValue() {
    Glitch(0.25f, seed = 7) shouldBe Glitch(0.25f, seed = 7)
  }

  @Test
  fun refusesAnIntensityOutOfRange() {
    shouldThrow<IllegalArgumentException> { Glitch(1.5f) }
    shouldThrow<IllegalArgumentException> { Glitch(-0.5f) }
  }

  @Test
  fun theBuilderAddsAGlitch() {
    EffectsBuilder().glitch(0.5f, seed = 9).build() shouldContainExactly listOf(Glitch(0.5f, seed = 9))
  }

  @Test
  fun theFragmentOpensOnItsVersionAndCarriesTheRowShift() {
    GlitchShader.FRAGMENT shouldStartWith "#version 300 es"
    GlitchShader.FRAGMENT shouldContain "glitchRowShift"
    GlitchShader.FRAGMENT shouldContain "i < ${GlitchSignal.maxSlices};"
    GlitchShader.FRAGMENT shouldContain "${TEAR_BELOW}u"
  }

  @Test
  fun theUniformsAreExactlyTheOnesTheFragmentDeclares() {
    val declared =
      Regex("""uniform float ([^;]+);""")
        .findAll(GlitchShader.FRAGMENT)
        .flatMap { match -> match.groupValues[1].split(',').map { it.trim() } }
        .toSet() - GlitchShader.FLIP_V

    GlitchShader.uniforms(Glitch(0.5f), 3.0).keys shouldBe declared
  }

  @Test
  fun theUniformsCarryTheBurstAndSplitAtTheFramesTick() {
    val glitch = Glitch(0.5f)
    val params = GlitchParams.of(glitch)
    val seconds = 17.25
    val tick = GlitchSignal.tickAt(seconds)
    val uniforms = GlitchShader.uniforms(glitch, seconds)

    uniforms.getValue("uTick") shouldBe tick.toFloat()
    uniforms.getValue("uSplit") shouldBe uniformFloat(GlitchSignal.channelSplit(glitch.seed, tick, params))
    uniforms.getValue("uStrength") shouldBe
      uniformFloat(GlitchSignal.burstAt(glitch.seed, tick, params)?.strength ?: 0.0)
    uniforms.getValue("uBandShift") shouldBe uniformFloat(params.bandShift)
  }
}
