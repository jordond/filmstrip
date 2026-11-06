package dev.jordond.filmstrip.effects.filters.crt

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it is showing on a CRT.
 *
 * The picture bows out on a curved tube with black past its edge, scanlines and a phosphor mask run over it, and the
 * corners fall off. The scanlines and the mask are the average over each pixel, so a smaller preview draws the same
 * picture at its own size. Nothing reads the clock, so a seek changes nothing.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Crt] is part of the clip's frame by then, and takes the look with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property intensity How strong the look is, from `0f` which changes nothing to `1f`.
 * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
 */
@Poko
public class Crt(
  public val intensity: Float = 1f,
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A CRT intensity has to be within 0 to 1, not $intensity." }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.crt"
  }
}

/**
 * Adds a [Crt] look at [intensity], from `0f` to `1f`.
 */
public fun EffectsBuilder.crt(intensity: Float = 1f): EffectsBuilder = add(Crt(intensity))
