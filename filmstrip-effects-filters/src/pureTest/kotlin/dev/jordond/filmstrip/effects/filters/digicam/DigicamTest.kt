package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a [Digicam] declares to the planner and the figures its controls turn into.
 */
class DigicamTest {
  @Test
  fun `declares its place beneath overlays and that it needs SDR`() {
    val digicam = Digicam()

    digicam.id shouldBe Digicam.ID
    digicam.stage shouldBe EffectStage.Composite
    digicam.beneathOverlays shouldBe true
    digicam.requiresSdr shouldBe true
  }

  @Test
  fun `is inert only with every control at zero`() {
    Digicam(sharpness = 0f, noise = 0f, clip = 0f).isInert shouldBe true
    Digicam(sharpness = 0.01f, noise = 0f, clip = 0f).isInert shouldBe false
    Digicam(sharpness = 0f, noise = 0.01f, clip = 0f).isInert shouldBe false
    Digicam(sharpness = 0f, noise = 0f, clip = 0.01f).isInert shouldBe false
    Digicam().isInert shouldBe false
    Digicam.Ccd.isInert shouldBe false
  }

  @Test
  fun `compares by value`() {
    Digicam(0.25f, 0.5f, 0.75f, 7) shouldBe Digicam(0.25f, 0.5f, 0.75f, 7)
  }

  @Test
  fun `refuses a control out of range`() {
    shouldThrow<IllegalArgumentException> { Digicam(sharpness = 1.5f) }
    shouldThrow<IllegalArgumentException> { Digicam(noise = -0.1f) }
    shouldThrow<IllegalArgumentException> { Digicam(clip = 2f) }
  }

  @Test
  fun `the preset pushes every control past the default`() {
    val preset = DigicamParams.of(Digicam.Ccd)
    val plain = DigicamParams.of(Digicam())

    (preset.haloGain > plain.haloGain) shouldBe true
    (preset.clipGain > plain.clipGain) shouldBe true
    (preset.noiseAmplitude > plain.noiseAmplitude) shouldBe true
  }

  @Test
  fun `each figure is linear in its control`() {
    val half = DigicamParams.at(sharpness = 0.5, noise = 0.5, clip = 0.5)
    val none = DigicamParams.at(sharpness = 0.0, noise = 0.0, clip = 0.0)

    half.haloGain shouldBe (HALO_GAIN_MAX / 2.0 plusOrMinus TOLERANCE)
    half.clipGain shouldBe (1.0 + CLIP_GAIN_MAX / 2.0 plusOrMinus TOLERANCE)
    half.noiseAmplitude shouldBe (NOISE_MAX / 2.0 plusOrMinus TOLERANCE)
    none.haloGain shouldBe 0.0
    none.clipGain shouldBe 1.0
    none.noiseAmplitude shouldBe 0.0
  }

  private companion object {
    const val TOLERANCE = 1e-12
  }
}
