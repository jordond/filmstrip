package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.media.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the editor's knobs author, checked without a composition on screen.
 */
class EditStateTest {
  @Test
  fun `every colour knob reaches the composition in the order the pipeline runs them`() {
    val edit = EditState().apply {
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

    assertEquals(
      listOf(
        EffectIds.BRIGHTNESS,
        EffectIds.RGB_ADJUSTMENT,
        EffectIds.CONTRAST,
        EffectIds.SATURATION,
        EffectIds.HUE_ROTATE,
        EffectIds.SEPIA,
        EffectIds.INVERT,
        EffectIds.COLOR_MATRIX,
      ),
      effects.map { it.id },
    )
    assertEquals(effects, effects.inCanonicalOrder())
    assertTrue(edit.colorGraded)
  }

  @Test
  fun `a knob at its default authors nothing`() {
    val edit = EditState()

    assertFalse(edit.colorGraded)
    assertEquals(emptyList(), edit.composition(SOURCE, sourceDuration = null).effects)

    edit.saturation = 0f
    assertEquals(listOf(Saturation(0f)), edit.colorEffects)

    edit.reset(sourceDuration = null)
    assertFalse(edit.colorGraded)
  }

  private companion object {
    val SOURCE = MediaSource.of("/tmp/clip.mp4")
  }
}
