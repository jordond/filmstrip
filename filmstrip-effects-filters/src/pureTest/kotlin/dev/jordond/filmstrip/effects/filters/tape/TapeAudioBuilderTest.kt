package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.AudioEffectsBuilder
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TapeAudioBuilderTest {
  @Test
  fun `an empty block adds the default tape`() {
    AudioEffectsBuilder().tapeAudio().build() shouldBe listOf(TapeAudio())
  }

  @Test
  fun `the block sets every value`() {
    val built =
      AudioEffectsBuilder()
        .tapeAudio {
          intensity = 0.6f
          tape = TapeSpeed.SP
          seed = 7
          bandLimit = 0.4f
          hiss = 0.3f
          wow = Wobble(0.8f, 0.5f)
          flutter = Wobble(0.2f)
          mono = false
        }.build()

    built shouldBe listOf(TapeAudio(0.6f, TapeSpeed.SP, 7, 0.4f, 0.3f, Wobble(0.8f, 0.5f), Wobble(0.2f), false))
  }

  @Test
  fun `effects keep the order they were added in`() {
    val built = AudioEffectsBuilder().tapeAudio { seed = 1 }.tapeAudio { seed = 2 }.build()

    built.map { (it as TapeAudio).seed } shouldBe listOf(1, 2)
  }
}
