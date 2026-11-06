package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a [Vhs] declares to the planner.
 */
class VhsTest {
  @Test
  fun `declares its place beneath overlays and that it needs SDR`() {
    val vhs = Vhs(intensity = 0.5f)

    vhs.stage shouldBe EffectStage.Composite
    vhs.beneathOverlays shouldBe true
    vhs.requiresSdr shouldBe true
  }

  @Test
  fun `is inert at zero intensity and not at a half`() {
    Vhs(intensity = 0f).isInert shouldBe true
    Vhs(intensity = 0.5f).isInert shouldBe false
  }

  @Test
  fun `compares by value`() {
    Vhs(intensity = 0.5f, tape = TapeSpeed.LP) shouldBe Vhs(intensity = 0.5f, tape = TapeSpeed.LP)
  }

  @Test
  fun `refuses an intensity out of range`() {
    shouldThrow<IllegalArgumentException> { Vhs(intensity = 1.5f) }
  }
}
