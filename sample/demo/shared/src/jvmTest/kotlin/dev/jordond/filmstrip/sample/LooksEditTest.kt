@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.film.film
import dev.jordond.filmstrip.effects.filters.glitch.glitch
import dev.jordond.filmstrip.effects.filters.nightvision.nightVision
import dev.jordond.filmstrip.effects.spatial.vignette
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.ui.screen.gradeContributes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The looks and lens controls, each checked against the effect the library builds from the same values.
 */
class LooksEditTest {
  @Test
  fun `an untouched edit adds no effect`() {
    effectsOf(EditState()).shouldBeEmpty()
  }

  @Test
  fun `a vignette left at its radius and softness is the library default`() {
    val edit = EditState().apply { vignette = 0.4f }

    effectsOf(edit) shouldContainExactly built { vignette(strength = 0.4f) }
  }

  @Test
  fun `a vignette carries its radius and softness`() {
    val edit =
      EditState().apply {
        vignette = 0.4f
        vignetteRadius = 0.9f
        vignetteSoftness = 0.3f
      }

    effectsOf(edit) shouldContainExactly built { vignette(0.4f, 0.9f, 0.3f) }
  }

  @Test
  fun `film left alone past its switch is the look the demo always drew`() {
    val edit = EditState().apply { filmEnabled = true }

    effectsOf(edit) shouldContainExactly built { film(grain = 0.5f, halation = 0.5f) }
  }

  @Test
  fun `film carries weave flicker dust and gate`() {
    val edit =
      EditState().apply {
        filmEnabled = true
        filmWeave = 0.4f
        filmFlicker = 0.3f
        filmDust = 0.6f
        filmGate = 0.2f
      }

    effectsOf(edit) shouldContainExactly
      built { film(grain = 0.5f, halation = 0.5f, weave = 0.4f, flicker = 0.3f, dust = 0.6f, gate = 0.2f) }
  }

  @Test
  fun `film carries breath and fade`() {
    val edit =
      EditState().apply {
        filmEnabled = true
        filmBreath = 0.4f
        filmFade = 0.6f
      }

    effectsOf(edit) shouldContainExactly built { film(grain = 0.5f, halation = 0.5f, breath = 0.4f, fade = 0.6f) }
  }

  @Test
  fun `night vision and glitch switched on are the library defaults`() {
    val edit =
      EditState().apply {
        nightVisionEnabled = true
        glitchEnabled = true
      }

    effectsOf(edit) shouldContainExactly
      built {
        nightVision()
        glitch()
      }
  }

  @Test
  fun `glitch carries its intensity and blocks and night vision its intensity`() {
    val edit =
      EditState().apply {
        nightVisionEnabled = true
        nightVisionIntensity = 0.6f
        glitchEnabled = true
        glitchIntensity = 0.4f
        glitchBlocks = 0.7f
      }

    effectsOf(edit) shouldContainExactly
      built {
        nightVision(0.6f)
        glitch(intensity = 0.4f, blocks = 0.7f)
      }
  }

  @Test
  fun `every look marks the adjust tool as contributing`() {
    val looks: List<EditState.() -> Unit> =
      listOf(
        { filmEnabled = true },
        { bloomEnabled = true },
        { crtEnabled = true },
        { lutEnabled = true },
        { nightVisionEnabled = true },
        { glitchEnabled = true },
        { blur = 0.05f },
        { vignette = 0.4f },
      )

    EditState().gradeContributes shouldBe false
    looks.forEach { look -> EditState().apply(look).gradeContributes shouldBe true }
  }

  private fun effectsOf(edit: EditState): List<EffectSpec> = edit.composition(clipsOf(SOURCE)).effects

  private fun built(block: EffectsBuilder.() -> Unit): List<EffectSpec> = EffectsBuilder().apply(block).build()

  private companion object {
    val SOURCE: MediaSource = MediaSource.Path("/clips/sample.mp4")
  }
}
