package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
    effects.inCanonicalOrder() shouldBe effects
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

  private companion object {
    val SOURCE = MediaSource.of("/tmp/clip.mp4")
  }
}
