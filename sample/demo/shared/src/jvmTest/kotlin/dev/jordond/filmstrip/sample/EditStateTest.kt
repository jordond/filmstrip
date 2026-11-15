package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * What the editor's knobs author, checked without a composition on screen.
 */
class EditStateTest {
  @Test
  fun `every color knob reaches the composition in the order the pipeline runs them`() {
    val edit =
      EditState().apply {
        brightness = 1.2f
        channelRed = 0.9f
        contrast = 1.1f
        saturation = 0.5f
        hueDegrees = 30f
        sepia = 0.4f
        invert = 0.1f
        customMatrix = ColorMatrix(rb = 0.5f)
      }

    val effects = edit.composition(SOURCE, sourceDuration = null).effects

    effects.map { it.id } shouldContainExactly
      listOf(
        EffectIds.BRIGHTNESS,
        EffectIds.RGB_ADJUSTMENT,
        EffectIds.CONTRAST,
        EffectIds.SATURATION,
        EffectIds.HUE_ROTATE,
        EffectIds.SEPIA,
        EffectIds.INVERT,
        EffectIds.COLOR_MATRIX,
      )
    canonicalOrder(effects) shouldBe effects
    edit.colorGraded shouldBe true
  }

  @Test
  fun `a knob at its default authors nothing`() {
    val edit = EditState()

    edit.colorGraded shouldBe false
    edit.composition(SOURCE, sourceDuration = null).effects.shouldBeEmpty()

    edit.saturation = 0f
    edit.colorEffects shouldBe listOf(Saturation(0f))

    edit.reset(sourceDuration = null)
    edit.colorGraded shouldBe false
  }

  @Test
  fun `the tape look plays the format and speed that were picked`() {
    val edit =
      EditState().apply {
        tapeEnabled = true
        tapeIntensity = 0.5f
        tapeTracking = TapeTrackingMode.Off
      }
    val vhs =
      edit
        .composition(SOURCE, sourceDuration = null)
        .effects
        .single()
        .shouldBeInstanceOf<Vhs>()
    vhs.tape shouldBe TapeSpeed.EP

    edit.selectTapeFormat(TapeFormat.Hi8)
    edit.tapeStock = Hi8Speed.LP
    val hi8 =
      edit
        .composition(SOURCE, sourceDuration = null)
        .effects
        .single()
        .shouldBeInstanceOf<Hi8>()
    hi8.tape shouldBe Hi8Speed.LP
    hi8.intensity shouldBe 0.5f
    hi8.tracking shouldBe Tracking.Off

    edit.selectTapeFormat(TapeFormat.Betamax)
    edit.tapeFormat shouldBe TapeFormat.Betamax
    val betamax =
      edit
        .composition(SOURCE, sourceDuration = null)
        .effects
        .single()
        .shouldBeInstanceOf<Betamax>()
    betamax.tape shouldBe BetaSpeed.II
  }

  @Test
  fun `the tape audio follows the format`() {
    val edit = EditState().apply { tapeAudioEnabled = true }

    edit.selectTapeFormat(TapeFormat.Hi8)
    val audio =
      edit
        .composition(SOURCE, sourceDuration = null)
        .audioEffects
        .single()
        .shouldBeInstanceOf<TapeAudio>()
    audio.tape shouldBe Hi8Speed.SP

    edit.reset(sourceDuration = null)
    edit.tapeFormat shouldBe TapeFormat.Vhs
    edit.tapeAudioStock shouldBe TapeSpeed.EP
  }

  private companion object {
    val SOURCE = MediaSource.of("/tmp/clip.mp4")
  }
}
