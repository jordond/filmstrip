package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effect.EffectStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a [Bloom] declares to the planner.
 */
class BloomTest {
  @Test
  fun `declares its place beneath overlays and that it needs SDR`() {
    val bloom = Bloom()

    bloom.id shouldBe Bloom.ID
    bloom.stage shouldBe EffectStage.Composite
    bloom.beneathOverlays shouldBe true
    bloom.requiresSdr shouldBe true
  }

  @Test
  fun `is inert with no intensity or with nothing above its threshold`() {
    Bloom(intensity = 0f).isInert shouldBe true
    Bloom(threshold = 1f).isInert shouldBe true
    Bloom().isInert shouldBe false
    Bloom(intensity = 0.01f, threshold = 0.99f, spread = 0f).isInert shouldBe false
    Bloom.Dreamy.isInert shouldBe false
  }

  @Test
  fun `compares by value`() {
    Bloom(0.25f, 0.5f, 0.75f) shouldBe Bloom(0.25f, 0.5f, 0.75f)
  }

  @Test
  fun `refuses a control out of range`() {
    shouldThrow<IllegalArgumentException> { Bloom(intensity = 1.5f) }
    shouldThrow<IllegalArgumentException> { Bloom(threshold = -0.1f) }
    shouldThrow<IllegalArgumentException> { Bloom(spread = 2f) }
  }

  @Test
  fun `dreamy starts lower and reaches wider than the default`() {
    val dreamy = BloomParams.of(Bloom.Dreamy)
    val plain = BloomParams.of(Bloom())

    (dreamy.threshold < plain.threshold) shouldBe true
    (dreamy.sigma > plain.sigma) shouldBe true
  }
}
