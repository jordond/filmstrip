package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.grayscale
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.sepia
import dev.jordond.filmstrip.effects.spatial.vignette

/**
 * The picture looks a swipe steps through, each a short chain of colour effects under the elements.
 *
 * @property label The name flashed on the picture when the look is picked.
 */
internal enum class StoryLook(
  val label: String,
  private val lower: EffectsBuilder.() -> Unit,
) {
  Original("Original", {}),
  Vivid("Vivid", { saturation(1.35f).contrast(1.1f) }),
  Warm("Warm", { sepia(0.35f).saturation(1.15f) }),
  Faded("Faded", { contrast(0.85f).brightness(1.06f).saturation(0.8f) }),
  Mono("Mono", { grayscale().contrast(1.15f) }),
  Noir("Noir", { grayscale().contrast(1.3f).vignette(0.6f) }),
  ;

  /**
   * The effects this look puts on the composition, none for [Original].
   */
  val effects: List<EffectSpec>
    get() = EffectsBuilder().apply(lower).build()

  /**
   * The look a swipe back lands on, wrapping from the first to the last.
   */
  fun previous(): StoryLook = entries[(ordinal + entries.size - 1) % entries.size]
}
