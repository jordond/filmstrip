package dev.jordond.filmstrip.effects.filters.pixel

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Makes the frame look like low resolution pixel art.
 *
 * The frame is cut into square cells, [cells] of them down its height, and every pixel in a cell takes the colour at
 * the cell's centre. A [palette] then snaps each cell to its nearest colour, or without one [levels] cuts each channel
 * down to that many steps. [dither] spreads the step between two colours over a 4 by 4 ordered pattern laid on the
 * cells. Cells are counted across the frame, so a smaller preview draws the same cells, and nothing reads the clock.
 *
 * The effect runs over the fill and under any overlay at the same scope, so a caption added next to it stays clean. A
 * clip overlay under a composition [Pixel] is part of the clip's frame by then, and takes the look with it.
 *
 * An HDR source tone maps to SDR first. `HdrMode.Auto` reports that as a concession naming this effect, and
 * `HdrMode.KeepHdr` is refused.
 *
 * Register the renderer with `filters()` on the builder. Without it `plan` refuses the effect by name.
 *
 * @property cells How many cells run down the frame's height, from [MIN_CELLS] to [MAX_CELLS]. The columns follow
 * from the frame's shape.
 * @property levels How many steps each channel keeps, from [MIN_LEVELS] to [MAX_LEVELS], or `0` to keep the colour.
 * It does nothing when a [palette] is set.
 * @property dither How strongly the ordered pattern pushes a colour toward its next step, from `0f` to `1f`.
 * @property palette The only colours a cell can end on, or null to leave that to [levels].
 * @throws IllegalArgumentException when a value is outside its range.
 */
@Poko
public class Pixel(
  public val cells: Int = DEFAULT_CELLS,
  public val levels: Int = 0,
  public val dither: Float = 0f,
  public val palette: PixelPalette? = null,
) : EffectSpec {
  init {
    require(cells in MIN_CELLS..MAX_CELLS) {
      "A pixel look needs $MIN_CELLS to $MAX_CELLS cells down the frame, not $cells."
    }
    require(levels == 0 || levels in MIN_LEVELS..MAX_LEVELS) {
      "A pixel look keeps 0 or $MIN_LEVELS to $MAX_LEVELS levels a channel, not $levels."
    }
    require(dither in 0f..1f) { "A pixel look's dither has to be within 0 to 1, not $dither." }
  }

  override val id: String get() = ID

  override val stage: EffectStage get() = EffectStage.Composite

  override val beneathOverlays: Boolean get() = true

  override val requiresSdr: Boolean get() = true

  override val isInert: Boolean get() = false

  public companion object {
    /**
     * The id every plan and notice names this effect by.
     */
    public const val ID: String = "filmstrip.filters.pixel"

    /**
     * How many cells run down the frame when none is given.
     */
    public const val DEFAULT_CELLS: Int = 144

    /**
     * The fewest cells down the frame.
     */
    public const val MIN_CELLS: Int = 8

    /**
     * The most cells down the frame, one a pixel at 1080p.
     */
    public const val MAX_CELLS: Int = 1080

    /**
     * The fewest steps a channel keeps when [levels] is on.
     */
    public const val MIN_LEVELS: Int = 2

    /**
     * The most steps a channel keeps.
     */
    public const val MAX_LEVELS: Int = 64
  }
}

/**
 * Adds a [Pixel] look with [cells] down the frame, cut to [levels] a channel or snapped to [palette], with [dither].
 */
public fun EffectsBuilder.pixel(
  cells: Int = Pixel.DEFAULT_CELLS,
  levels: Int = 0,
  dither: Float = 0f,
  palette: PixelPalette? = null,
): EffectsBuilder = add(Pixel(cells, levels, dither, palette))
