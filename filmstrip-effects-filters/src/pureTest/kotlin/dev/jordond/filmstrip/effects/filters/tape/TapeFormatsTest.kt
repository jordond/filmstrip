package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a [Betamax] and a [Hi8] declare to the planner, and the builders that describe them.
 */
class TapeFormatsTest {
  @Test
  fun `each format carries its own id`() {
    Betamax().id shouldBe EffectId("filmstrip.filters.betamax")
    Hi8().id shouldBe EffectId("filmstrip.filters.hi8")
  }

  @Test
  fun `each format sits beneath overlays and needs SDR`() {
    for (look in listOf<TapeLook>(Betamax(intensity = 0.5f), Hi8(intensity = 0.5f))) {
      withClue(look.id) {
        look.stage shouldBe EffectStage.Composite
        look.beneathOverlays shouldBe true
        look.requiresSdr shouldBe true
      }
    }
  }

  @Test
  fun `each format is inert at zero intensity and not at a half`() {
    Betamax(intensity = 0f).isInert shouldBe true
    Betamax(intensity = 0.5f).isInert shouldBe false
    Hi8(intensity = 0f).isInert shouldBe true
    Hi8(intensity = 0.5f).isInert shouldBe false
  }

  @Test
  fun `each format refuses an intensity out of range`() {
    shouldThrow<IllegalArgumentException> { Betamax(intensity = 1.5f) }
    shouldThrow<IllegalArgumentException> { Hi8(intensity = -0.1f) }
  }

  @Test
  fun `each format redraws while held only on the wall clock`() {
    Betamax().redrawsWhileHeld shouldBe false
    Betamax(clock = TapeClock.Wall).redrawsWhileHeld shouldBe true
    Hi8(clock = TapeClock.Wall).redrawsWhileHeld shouldBe true
  }

  @Test
  fun `the defaults share a seed with a vhs`() {
    Betamax() shouldBe Betamax(1f, BetaSpeed.II, Vhs.DEFAULT_SEED, Tracking.Events(), TapeClock.Composition)
    Hi8() shouldBe Hi8(1f, Hi8Speed.SP, Vhs.DEFAULT_SEED, Tracking.Events(), TapeClock.Composition)
    TapeAudio().seed shouldBe Betamax().seed
  }

  @Test
  fun `an empty block builds the default look`() {
    EffectsBuilder().betamax().build() shouldBe listOf(Betamax())
    EffectsBuilder().hi8().build() shouldBe listOf(Hi8())
  }

  @Test
  fun `the betamax block sets every value`() {
    val built =
      EffectsBuilder()
        .betamax {
          intensity = 0.6f
          tape = BetaSpeed.III
          seed = 7
          tracking = rolling { snow = 0.8f }
          clock = TapeClock.Wall
        }.build()

    built shouldBe
      listOf(Betamax(0.6f, BetaSpeed.III, 7, Tracking.Rolling(snow = 0.8f), TapeClock.Wall))
  }

  @Test
  fun `the hi8 block sets every value`() {
    val built =
      EffectsBuilder()
        .hi8 {
          intensity = 0.4f
          tape = Hi8Speed.LP
          seed = 9
          tracking = Tracking.Off
          clock = TapeClock.Wall
        }.build()

    built shouldBe listOf(Hi8(0.4f, Hi8Speed.LP, 9, Tracking.Off, TapeClock.Wall))
  }

  @Test
  fun `a camcorder inside a format is recorded ahead of the tape`() {
    EffectsBuilder().betamax { camcorder { rec() } }.build().map { it.id } shouldContainExactly
      listOf(Camcorder.ID, Betamax.ID)
    EffectsBuilder().hi8 { camcorder { rec() } }.build().map { it.id } shouldContainExactly
      listOf(Camcorder.ID, Hi8.ID)
  }

  @Test
  fun `a second camcorder inside one format fails by the format's name`() {
    val error =
      shouldThrow<IllegalArgumentException> {
        EffectsBuilder().hi8 {
          camcorder { rec() }
          camcorder { rec() }
        }
      }

    error.message shouldBe "A hi8 records one camcorder, and this hi8 already has one."
  }
}
