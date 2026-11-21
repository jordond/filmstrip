@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.AudioEffectsBuilder
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.camcorder.ClockStyle
import dev.jordond.filmstrip.effects.filters.camcorder.DateStyle
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt
import dev.jordond.filmstrip.effects.filters.camcorder.TransportMode
import dev.jordond.filmstrip.effects.filters.camcorder.camcorder
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudio
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The tape, tape audio and camcorder controls, each checked against what the library builds from the same values.
 */
class TapeEditTest {
  @Test
  fun `a tape look switched on and left alone is the library default at EP`() {
    val edit = EditState().apply { tapeEnabled = true }

    effectsOf(edit) shouldContainExactly built { vhs() }
  }

  @Test
  fun `a settle reaches every tape format`() {
    val edit =
      EditState().apply {
        tapeEnabled = true
        tapeSettleEnabled = true
        tapeSettleFrom = 0.6f
        tapeSettleSeconds = 1.5f
      }

    effectsOf(edit) shouldContainExactly built { vhs { settle(0.6f, 1.5.seconds) } }

    edit.selectTapeFormat(TapeFormat.Betamax)
    effectsOf(edit) shouldContainExactly
      built {
        betamax {
          tape = BetaSpeed.II
          settle(0.6f, 1.5.seconds)
        }
      }

    edit.selectTapeFormat(TapeFormat.Hi8)
    effectsOf(edit) shouldContainExactly
      built {
        hi8 {
          tape = Hi8Speed.SP
          settle(0.6f, 1.5.seconds)
        }
      }
  }

  @Test
  fun `the tape clock reaches the look`() {
    val other = TapeClock.entries.last()
    val edit =
      EditState().apply {
        tapeEnabled = true
        tapeClock = other
      }

    effectsOf(edit) shouldContainExactly built { vhs { clock = other } }
  }

  @Test
  fun `tape audio left alone is the library default`() {
    val edit = EditState().apply { tapeAudioEnabled = true }

    audioEffectsOf(edit) shouldContainExactly AudioEffectsBuilder().apply { tapeAudio() }.build()
  }

  @Test
  fun `tape audio carries every control`() {
    val edit =
      EditState().apply {
        tapeAudioEnabled = true
        tapeAudioStock = TapeSpeed.LP
        tapeAudioIntensity = 0.7f
        tapeAudioHiss = 0.4f
        tapeAudioBandLimit = 0.6f
        tapeAudioWow = 0.3f
        tapeAudioFlutter = 0.8f
        tapeAudioMono = false
      }

    audioEffectsOf(edit) shouldContainExactly
      AudioEffectsBuilder()
        .apply {
          tapeAudio {
            tape = TapeSpeed.LP
            intensity = 0.7f
            hiss = 0.4f
            bandLimit = 0.6f
            wow = Wobble(0.3f)
            flutter = Wobble(0.8f)
            mono = false
          }
        }.build()
  }

  @Test
  fun `a camcorder left alone shows what the demo always showed`() {
    val edit = EditState().apply { camcorderDisplay.enabled = true }

    effectsOf(edit) shouldContainExactly
      built {
        camcorder {
          rec()
          date()
          clock()
        }
      }
  }

  @Test
  fun `a camcorder carries its transport styles date badge and label`() {
    val recorded = RecordedAt(1989, 12, 25, 8, 15, 0)
    val edit =
      EditState().apply {
        tapeStock = TapeSpeed.LP
        camcorderDisplay.enabled = true
        camcorderDisplay.transport = CamcorderTransport.Pause
        camcorderDisplay.dateStyle = DateStyle.DayMonthYear
        camcorderDisplay.clockStyle = ClockStyle.TwentyFourHour
        camcorderDisplay.recordedAt = recorded
        camcorderDisplay.tapeBadge = true
        camcorderDisplay.label = "HOME"
      }

    effectsOf(edit) shouldContainExactly
      built {
        camcorder {
          recordedAt = recorded
          transport(TransportMode.Pause)
          tape(TapeSpeed.LP)
          date(style = DateStyle.DayMonthYear)
          clock(style = ClockStyle.TwentyFourHour)
          label("HOME")
        }
      }
  }

  private fun effectsOf(edit: EditState): List<EffectSpec> = edit.composition(SOURCE, sourceDuration = null).effects

  private fun audioEffectsOf(edit: EditState) = edit.composition(SOURCE, sourceDuration = null).audioEffects

  private fun built(block: EffectsBuilder.() -> Unit): List<EffectSpec> = EffectsBuilder().apply(block).build()

  private companion object {
    val SOURCE: MediaSource = MediaSource.Path("/clips/sample.mp4")
  }
}
