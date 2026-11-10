package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

/**
 * What a [Crt] declares to the planner, and what its shader carries from the signal.
 */
class CrtTest {
  @Test
  fun declaresItsPlaceBeneathOverlaysAndThatItNeedsSdr() {
    val crt = Crt()

    crt.stage shouldBe EffectStage.Composite
    crt.beneathOverlays shouldBe true
    crt.requiresSdr shouldBe true
  }

  @Test
  fun isInertOnlyAtZero() {
    Crt(0f).isInert shouldBe true
    Crt(0.01f).isInert shouldBe false
    Crt().isInert shouldBe false
  }

  @Test
  fun comparesByValue() {
    Crt(0.25f) shouldBe Crt(0.25f)
  }

  @Test
  fun refusesAnIntensityOutOfRange() {
    shouldThrow<IllegalArgumentException> { Crt(1.5f) }
    shouldThrow<IllegalArgumentException> { Crt(-0.5f) }
  }

  @Test
  fun theFragmentOpensOnItsVersionAndCarriesTheSharedReadAndTheSignalsConstants() {
    val fragment = CrtShader.FRAGMENT

    fragment shouldStartWith "#version 300 es"
    fragment shouldContain "vec4 straightRead(sampler2D frame, vec2 uv)"
    fragment shouldContain "straightRead(${CrtShader.TEXTURE}, source)"
    fragment shouldContain glslFloat(TWO_PI * CRT_LINES)
    fragment shouldContain glslFloat(TWO_PI * CRT_TRIADS)
    fragment shouldContain glslFloat(CrtSignal.maskPhase(2))
  }

  @Test
  fun theUniformsCarryTheFootprintAverageOfTheFrameSize() {
    val uniforms = CrtShader.uniforms(Crt(), WIDTH, HEIGHT)

    uniforms.getValue("uScanlineAverage")[0] shouldBe
      asUniform(CrtSignal.footprintAverage(CrtSignal.LINES, 1.0 / HEIGHT))
    uniforms.getValue("uMaskAverage")[0] shouldBe asUniform(CrtSignal.footprintAverage(CrtSignal.TRIADS, 1.0 / WIDTH))
    uniforms.getValue("uBarrelK")[0] shouldBe asUniform(CrtParams.of(Crt()).barrelK)
  }

  // Through an array, since toFloat alone keeps the whole double on js and a uniform only has 32 bits.
  private fun asUniform(value: Double): Float = floatArrayOf(value.toFloat())[0]
}

private const val WIDTH = 1366
private const val HEIGHT = 768
