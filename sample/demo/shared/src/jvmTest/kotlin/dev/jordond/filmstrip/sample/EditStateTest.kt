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
import kotlin.time.Duration.Companion.seconds

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

  @Test
  fun `the end card holds its color after the clip and adds its length`() {
    val edit =
      EditState().apply {
        endCardEnabled = true
        endCardColor = 0xFF1A1A21.toInt()
        endCardSeconds = 1.5f
      }

    val clips = edit.composition(SOURCE, sourceDuration = 10.seconds).clips
    clips.map { it.source } shouldContainExactly listOf(SOURCE, clips.last().source)
    val card = clips.last().source.shouldBeInstanceOf<MediaSource.Solid>()
    card.color shouldBe 0xFF1A1A21.toInt()
    card.duration shouldBe 1.5.seconds
    edit.editedDuration(10.seconds) shouldBe 11.5.seconds
    edit.composition(SOURCE, sourceDuration = 10.seconds, trimmed = false).clips.map { it.source } shouldContainExactly
      listOf(SOURCE)
  }

  @Test
  fun `the output frame reaches the composition and the stage's aspect`() {
    val edit = EditState()
    edit.composition(SOURCE, sourceDuration = null).frame shouldBe OutputFrame.Auto

    edit.frameMode = FrameMode.Aspect
    edit.frameAspect = AspectRatio.Square
    edit
      .composition(SOURCE, sourceDuration = null)
      .frame
      .shouldBeInstanceOf<OutputFrame.Aspect>()
      .aspect shouldBe
      AspectRatio.Square
    edit.outputAspect(sourceAspect = 16f / 9f) shouldBe 1f

    edit.frameMode = FrameMode.Fixed
    edit.frameSize = Size(1080, 1920)
    edit
      .composition(SOURCE, sourceDuration = null)
      .frame
      .shouldBeInstanceOf<OutputFrame.Fixed>()
      .size shouldBe
      Size(1080, 1920)
    edit.outputAspect(sourceAspect = 16f / 9f) shouldBe 1080f / 1920f
    edit.outputAspect(sourceAspect = 16f / 9f, cropped = false) shouldBe 16f / 9f
    edit.composition(SOURCE, sourceDuration = null, cropped = false).frame shouldBe OutputFrame.Auto
  }

  private companion object {
    val SOURCE = MediaSource.of("/tmp/clip.mp4")
  }
}
