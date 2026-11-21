@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.OutputFrame
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
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Size
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
        grade.brightness = 1.2f
        grade.channelRed = 0.9f
        grade.contrast = 1.1f
        grade.saturation = 0.5f
        grade.hueDegrees = 30f
        grade.sepia = 0.4f
        grade.invert = 0.1f
        grade.customMatrix = ColorMatrix(rb = 0.5f)
      }

    val effects = edit.composition(clipsOf(SOURCE)).effects

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
    edit.grade.graded shouldBe true
  }

  @Test
  fun `a knob at its default authors nothing`() {
    val edit = EditState()

    edit.grade.graded shouldBe false
    edit.composition(clipsOf(SOURCE)).effects.shouldBeEmpty()

    edit.grade.saturation = 0f
    edit.grade.effects shouldBe listOf(Saturation(0f))

    edit.reset()
    edit.grade.graded shouldBe false
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
        .composition(clipsOf(SOURCE))
        .effects
        .single()
        .shouldBeInstanceOf<Vhs>()
    vhs.tape shouldBe TapeSpeed.EP

    edit.selectTapeFormat(TapeFormat.Hi8)
    edit.tapeStock = Hi8Speed.LP
    val hi8 =
      edit
        .composition(clipsOf(SOURCE))
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
        .composition(clipsOf(SOURCE))
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
        .composition(clipsOf(SOURCE))
        .audioEffects
        .single()
        .shouldBeInstanceOf<TapeAudio>()
    audio.tape shouldBe Hi8Speed.SP

    edit.reset()
    edit.tapeFormat shouldBe TapeFormat.Vhs
    edit.tapeAudioStock shouldBe TapeSpeed.EP
  }

  @Test
  fun `the output frame reaches the composition and the stage's aspect`() {
    val edit = EditState()
    edit.composition(clipsOf(SOURCE)).frame shouldBe OutputFrame.Auto

    edit.frameMode = FrameMode.Aspect
    edit.frameAspect = AspectRatio.Square
    edit
      .composition(clipsOf(SOURCE))
      .frame
      .shouldBeInstanceOf<OutputFrame.Aspect>()
      .aspect shouldBe
      AspectRatio.Square
    edit.outputAspect(sourceAspect = 16f / 9f) shouldBe 1f

    edit.frameMode = FrameMode.Fixed
    edit.frameSize = Size(1080, 1920)
    edit
      .composition(clipsOf(SOURCE))
      .frame
      .shouldBeInstanceOf<OutputFrame.Fixed>()
      .size shouldBe
      Size(1080, 1920)
    edit.outputAspect(sourceAspect = 16f / 9f) shouldBe 1080f / 1920f
    edit.outputAspect(sourceAspect = 16f / 9f, cropped = false) shouldBe 16f / 9f
    edit.composition(clipsOf(SOURCE), cropped = false).frame shouldBe OutputFrame.Auto
  }

  private companion object {
    val SOURCE = MediaSource.of("/tmp/clip.mp4")
  }
}
