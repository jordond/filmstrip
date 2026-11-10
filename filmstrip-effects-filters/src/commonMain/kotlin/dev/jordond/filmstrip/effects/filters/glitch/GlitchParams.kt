package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The strengths of one [Glitch], in fractions of the frame width.
 *
 * Built by [GlitchParams.at], and read by every lowering so they all draw the same bursts.
 *
 * @property split How far red and blue sit from green outside a burst, a fraction of the width.
 * @property burstSplit How much further red and blue move apart at the height of a burst, a fraction of the width.
 * @property bandShift The largest slide of a slice at the height of a burst, a fraction of the width.
 * @property tearShift The largest slide of a torn row at the height of a burst, a fraction of the width.
 * @property burstChance The chance a burst window holds a burst.
 */
@InternalFilmstripApi
public class GlitchParams private constructor(
  public val split: Double,
  public val burstSplit: Double,
  public val bandShift: Double,
  public val tearShift: Double,
  public val burstChance: Double,
) {
  /**
   * How far any read can move from its own column, a fraction of the width. A row takes its slice's slide and a
   * tear's on top, and red and blue move by the split either way.
   */
  public val widestReach: Double
    get() = bandShift + tearShift + split + burstSplit

  /**
   * Builds the values a [Glitch] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Glitch] at [intensity] draws with.
     *
     * Every slide and split scales linearly, so intensity 0 is the source. The burst chance scales by intensity
     * squared. The window, the burst lengths and the slice heights stay fixed.
     */
    public fun at(intensity: Double): GlitchParams =
      GlitchParams(
        split = SPLIT * intensity,
        burstSplit = BURST_SPLIT * intensity,
        bandShift = BAND_SHIFT * intensity,
        tearShift = TEAR_SHIFT * intensity,
        burstChance = BURST_CHANCE * intensity * intensity,
      )

    /**
     * The values [glitch] draws with.
     */
    public fun of(glitch: Glitch): GlitchParams = at(glitch.intensity.toDouble())
  }
}

// Strengths at intensity 1, in fractions of the width.
private const val SPLIT = 0.002
private const val BURST_SPLIT = 0.012
private const val BAND_SHIFT = 0.06
private const val TEAR_SHIFT = 0.1
private const val BURST_CHANCE = 0.35
