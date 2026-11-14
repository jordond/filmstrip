package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalFilmstripApi::class)
class FilterAudioEffectResolverTest {
  private val attributes = AudioAttributes(TimeRange(0.seconds, 2.seconds))

  @Test
  fun `a tape audio is claimed and runs`() {
    val steady = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))

    (FilterAudioEffectResolver().resolve(steady, attributes) is AudioEffectResolution.Resolved) shouldBe true
    val bent = FilterAudioEffectResolver().resolve(TapeAudio(), attributes)
    (bent is AudioEffectResolution.Resolved || bent is AudioEffectResolution.Degraded) shouldBe true
  }

  @Test
  fun `another effect is left to the next resolver`() {
    FilterAudioEffectResolver().resolve(Other, attributes).shouldBeNull()
  }

  private data object Other : AudioEffectSpec {
    override val id: EffectId get() = EffectId("test.other")
  }
}
