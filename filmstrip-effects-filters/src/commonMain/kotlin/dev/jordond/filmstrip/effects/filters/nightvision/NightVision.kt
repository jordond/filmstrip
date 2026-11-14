package dev.jordond.filmstrip.effects.filters.nightvision

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like it was seen through a night vision scope.
 *
 * The picture's luma is lifted, grained and drawn through a green phosphor ramp that runs from black to near white, so
 * its colour no longer matters, and the edges fall off under a fixed vignette. The grain is a function of the frame's
 * composition time and its place on a fixed raster, and the vignette reads the frame as fractions, so a seek or a
 * smaller preview shows the same frame.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [NightVision] is part of the clip's frame by then, and takes the look with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name. An Apple device
 * that cannot compile a Core Image Metal kernel, which takes an Apple6 GPU on iOS or on macOS 12, refuses it by name
 * too.
 *
 * @property intensity How much of the look is mixed over the frame, from `0f` which leaves it alone to `1f`.
 * @property seed Picks the pattern of grain. Equal seeds draw the same grain.
 * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
 */
@Poko
public class NightVision(
  public val intensity: Float = 1f,
  public val seed: Int = DEFAULT_SEED,
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A night vision intensity has to be within 0 to 1, not $intensity." }
  }

  override val id: EffectId get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public val ID: EffectId = EffectId("filmstrip.filters.nightvision")

    /**
     * The seed a [NightVision] uses unless it is given one.
     */
    public const val DEFAULT_SEED: Int = 1965
  }
}

/**
 * Adds a [NightVision] look at [intensity] from `0f` to `1f`, drawing the grain [seed] picks.
 */
public fun EffectsBuilder.nightVision(
  intensity: Float = 1f,
  seed: Int = NightVision.DEFAULT_SEED,
): EffectsBuilder = add(NightVision(intensity, seed))
