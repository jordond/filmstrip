package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TapeAudioTest {
  @Test
  fun `the defaults pair with a default Vhs`() {
    val tape = TapeAudio()

    tape.tape shouldBe Vhs().tape
    tape.seed shouldBe Vhs().seed
    tape.mono shouldBe true
    tape.id shouldBe EffectId("filmstrip.filters.tapeAudio")
  }

  @Test
  fun `a scaler outside zero to one is refused`() {
    assertFailsWith<IllegalArgumentException> { TapeAudio(intensity = 1.01f) }
    assertFailsWith<IllegalArgumentException> { TapeAudio(intensity = -0.01f) }
    assertFailsWith<IllegalArgumentException> { TapeAudio(bandLimit = 1.5f) }
    assertFailsWith<IllegalArgumentException> { TapeAudio(hiss = -1f) }
    assertFailsWith<IllegalArgumentException> { Wobble(depth = 2f) }
    assertFailsWith<IllegalArgumentException> { TapeAudio(intensity = Float.NaN) }
  }

  @Test
  fun `a rate outside the range is refused`() {
    assertFailsWith<IllegalArgumentException> { Wobble(rateHz = 0f) }
    assertFailsWith<IllegalArgumentException> { Wobble(rateHz = -2f) }
    assertFailsWith<IllegalArgumentException> { Wobble(rateHz = 0.05f) }
    assertFailsWith<IllegalArgumentException> { Wobble(rateHz = 101f) }
    assertFailsWith<IllegalArgumentException> { Wobble(rateHz = Float.NaN) }
    Wobble(rateHz = Wobble.MIN_RATE_HZ).rateHz shouldBe 0.1f
    Wobble(rateHz = Wobble.MAX_RATE_HZ).rateHz shouldBe 100f
  }

  @Test
  fun `it is inert at no intensity or with every part off`() {
    TapeAudio(intensity = 0f).isInert shouldBe true
    TapeAudio(bandLimit = 0f, hiss = 0f, wow = Wobble(0f), flutter = Wobble(0f), mono = false).isInert shouldBe true
    TapeAudio(bandLimit = 0f, hiss = 0f, wow = Wobble(0f), flutter = Wobble(0f)).isInert shouldBe false
    TapeAudio(intensity = 0.01f).isInert shouldBe false
  }

  @Test
  fun `specs compare by value`() {
    TapeAudio(wow = Wobble(0.5f, 1.2f)) shouldBe TapeAudio(wow = Wobble(0.5f, 1.2f))
    TapeAudio(wow = Wobble(0.5f, 1.2f)).hashCode() shouldBe TapeAudio(wow = Wobble(0.5f, 1.2f)).hashCode()
    TapeAudio(tape = TapeSpeed.SP) shouldNotBe TapeAudio(tape = TapeSpeed.LP)
    TapeAudio(flutter = Wobble(0.5f)) shouldNotBe TapeAudio(flutter = Wobble(0.6f))
  }
}
