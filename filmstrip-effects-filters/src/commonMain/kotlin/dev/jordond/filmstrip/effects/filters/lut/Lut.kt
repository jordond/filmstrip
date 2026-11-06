package dev.jordond.filmstrip.effects.filters.lut

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Maps the frame's colours through a [CubeLut], read from a `.cube` file.
 *
 * Every backend reads the table trilinearly, and [intensity] mixes the table with one that changes nothing. The effect
 * depends on each pixel's colour alone, so a seek or a smaller preview draws the same colours.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean.
 * Colour effects at the same scope run before it, and filters at the same scope run in the order they were added.
 *
 * The table maps SDR codes, so an HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming
 * this effect, and `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property table The colour table.
 * @property intensity How strong the look is, from `0f` which changes nothing to `1f`.
 * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
 */
@Poko
public class Lut(
  public val table: CubeLut,
  public val intensity: Float = 1f,
) : EffectSpec {
  init {
    require(intensity in 0f..1f) { "A LUT intensity has to be within 0 to 1, not $intensity." }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = intensity == 0f || table.isIdentity

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.lut"
  }
}

/**
 * Adds a [Lut] of [table] at [intensity], from `0f` to `1f`.
 */
public fun EffectsBuilder.lut(
  table: CubeLut,
  intensity: Float = 1f,
): EffectsBuilder = add(Lut(table, intensity))
