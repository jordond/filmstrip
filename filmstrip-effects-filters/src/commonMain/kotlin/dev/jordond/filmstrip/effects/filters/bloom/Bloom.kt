package dev.jordond.filmstrip.effects.filters.bloom

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the bright parts of the frame glow.
 *
 * Everything brighter than [threshold] spills soft light around itself in its own colour, the way a lens with a mist
 * filter or a little haze on it does. The glow's width is a fraction of the frame width and it reads no clock, so a
 * seek or a smaller preview shows the same frame.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Bloom] is part of the clip's frame by then, and glows with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property intensity How strong the glow is, from `0f` which adds none to `1f`.
 * @property threshold How bright a part of the frame has to be to glow, from `0f` where everything does to `1f` where
 * nothing does.
 * @property spread How far the glow reaches, from `0f` which keeps it tight to `1f`.
 * @throws IllegalArgumentException when a control is outside `0f..1f`.
 */
@Poko
public class Bloom(
  public val intensity: Float = 0.5f,
  public val threshold: Float = 0.7f,
  public val spread: Float = 0.5f,
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A bloom intensity has to be within 0 to 1, not $intensity." }
    require(threshold in 0f..1f) { "A bloom threshold has to be within 0 to 1, not $threshold." }
    require(spread in 0f..1f) { "A bloom spread has to be within 0 to 1, not $spread." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f || threshold == 1f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.bloom")

    /**
     * A wide, heavy glow that starts in the midtones, for a soft and hazy picture.
     */
    public val Dreamy: Bloom = Bloom(intensity = 0.8f, threshold = 0.4f, spread = 1f)
  }
}

/**
 * Adds a [Bloom] with these controls, each from `0f` to `1f`.
 */
public fun EffectsBuilder.bloom(
  intensity: Float = 0.5f,
  threshold: Float = 0.7f,
  spread: Float = 0.5f,
): EffectsBuilder = add(Bloom(intensity, threshold, spread))
