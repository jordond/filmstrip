package dev.jordond.filmstrip.effects.filters.film

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it was shot on film.
 *
 * Halation glows warm around the brightest parts of the picture, and grain lands over the result, heaviest in the
 * midtones. The grain is a function of the frame's composition time and its place on a fixed raster, and the glow's
 * width a fraction of the frame width, so a seek or a smaller preview shows the same frame.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Film] is part of the clip's frame by then, and takes the look with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property grain How strong the grain is, from `0f` which adds none to `1f`.
 * @property halation How strong the glow is, from `0f` which adds none to `1f`.
 * @throws IllegalArgumentException when [grain] or [halation] is outside `0f..1f`.
 */
@Poko
public class Film(
  public val grain: Float = 0.5f,
  public val halation: Float = 0.5f,
) : EffectSpec {
  init {
    require(grain in 0f..1f) { "A film grain has to be within 0 to 1, not $grain." }
    require(halation in 0f..1f) { "A film halation has to be within 0 to 1, not $halation." }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = grain == 0f && halation == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.film"
  }
}

/**
 * Adds a [Film] look with [grain] and [halation], each from `0f` to `1f`.
 */
public fun EffectsBuilder.film(
  grain: Float = 0.5f,
  halation: Float = 0.5f,
): EffectsBuilder = add(Film(grain, halation))
