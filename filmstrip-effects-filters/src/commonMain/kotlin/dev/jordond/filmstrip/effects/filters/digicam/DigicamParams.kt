package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The figures one [Digicam] draws with, read by every lowering so they all draw the same frame.
 *
 * @property haloGain How much of the difference between a code and its blur is added back to the code.
 * @property clipGain What every code is multiplied by before it clips at white.
 * @property noiseAmplitude The largest chroma offset the noise adds, where the frame is black.
 */
@InternalFilmstripApi
public class DigicamParams private constructor(
  public val haloGain: Double,
  public val clipGain: Double,
  public val noiseAmplitude: Double,
) {
  /**
   * Builds the values a [Digicam] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Digicam] at these controls draws with, each linear in its control.
     */
    public fun at(
      sharpness: Double,
      noise: Double,
      clip: Double,
    ): DigicamParams = DigicamParams(HALO_GAIN_MAX * sharpness, 1.0 + CLIP_GAIN_MAX * clip, NOISE_MAX * noise)

    /**
     * The values [digicam] draws with.
     */
    public fun of(digicam: Digicam): DigicamParams =
      at(digicam.sharpness.toDouble(), digicam.noise.toDouble(), digicam.clip.toDouble())
  }
}
