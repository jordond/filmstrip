package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.filters.internal.GlowField
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import dev.jordond.filmstrip.effects.filters.internal.smoothstep
import kotlin.math.max
import kotlin.math.min

// How much of the blurred response is laid over the frame at an intensity of 1.
internal const val BLOOM_GAIN = 1.5

// How far above the threshold a luma has to sit to give its whole colour. The knee ends at white when the threshold
// sits closer to it than that, and stays at least BLOOM_KNEE_FLOOR wide so the response is defined at a threshold of 1.
internal const val BLOOM_KNEE = 0.2
internal const val BLOOM_KNEE_FLOOR = 0.01

// The blur's sigma as a fraction of the frame width, at a spread of 0 and what a spread of 1 adds to it.
internal const val BLOOM_SIGMA_MIN = 0.01
internal const val BLOOM_SIGMA_REACH = 0.03

/**
 * Every number a bloom is made of, computed once here and consumed by all four lowerings.
 *
 * It all runs on SDR encoded codes. The frame gives the blur its own colour wherever its luma passes the threshold,
 * and the blurred light is screened back over the frame.
 */
@InternalFilmstripApi
public object BloomSignal {
  /**
   * The blur's sigma at a [spread] from 0 to 1, as a fraction of the frame width.
   */
  public fun sigmaOf(spread: Double): Double = BLOOM_SIGMA_MIN + BLOOM_SIGMA_REACH * spread

  /**
   * The blur a frame [widthPx] wide runs at a [spread] from 0 to 1.
   */
  public fun blurPlan(
    spread: Double,
    widthPx: Int,
  ): BlurPlan = GlowField.blurPlan(sigmaOf(spread), widthPx)

  /**
   * The luma where a pixel under [threshold] gives the blur its whole colour.
   */
  public fun kneeEnd(threshold: Double): Double = max(min(1.0, threshold + BLOOM_KNEE), threshold + BLOOM_KNEE_FLOOR)

  /**
   * The light a code triple gives the blur under [threshold], as red, green and blue.
   *
   * The triple keeps its own colour, scaled by a smoothstep of its luma from [threshold] up to [kneeEnd]. It reads
   * colour already multiplied by its alpha.
   */
  public fun response(
    red: Double,
    green: Double,
    blue: Double,
    threshold: Double,
  ): DoubleArray {
    val pass = smoothstep(threshold, kneeEnd(threshold), lumaOf(red, green, blue))
    return doubleArrayOf(red * pass, green * pass, blue * pass)
  }

  /**
   * [red], [green] and [blue] with [blurred], the blur of [response] over this pixel, screened over them at [params]'
   * gain.
   */
  public fun bloomed(
    red: Double,
    green: Double,
    blue: Double,
    blurred: DoubleArray,
    params: BloomParams,
  ): DoubleArray =
    doubleArrayOf(
      screened(red, params.gain * blurred[0]),
      screened(green, params.gain * blurred[1]),
      screened(blue, params.gain * blurred[2]),
    )

  /**
   * The bloom's light across a frame [width] by [height] under [params], for [bloomed] to read at each pixel.
   *
   * @param pixel Reads the R'G'B' of a pixel counted from the top, already multiplied by its alpha.
   */
  public fun glow(
    width: Int,
    height: Int,
    params: BloomParams,
    pixel: (x: Int, y: Int) -> DoubleArray,
  ): GlowField =
    GlowField.of(
      width,
      height,
      params.sigma,
      { red, green, blue -> response(red, green, blue, params.threshold) },
      pixel,
    )

  private fun screened(
    code: Double,
    light: Double,
  ): Double = code + min(1.0, light) * (1.0 - code)
}

/**
 * The figures one [Bloom] draws with, read by every lowering so they all draw the same frame.
 *
 * @property gain How much of the blurred response is screened over the frame.
 * @property threshold The luma where a pixel starts to give the blur light.
 * @property kneeEnd The luma where a pixel gives the blur its whole colour.
 * @property sigma The blur's sigma, as a fraction of the frame width.
 */
@InternalFilmstripApi
public class BloomParams private constructor(
  public val gain: Double,
  public val threshold: Double,
  public val kneeEnd: Double,
  public val sigma: Double,
) {
  /**
   * Builds the values a [Bloom] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Bloom] at these controls draws with, the gain linear in [intensity].
     */
    public fun at(
      intensity: Double,
      threshold: Double,
      spread: Double,
    ): BloomParams =
      BloomParams(BLOOM_GAIN * intensity, threshold, BloomSignal.kneeEnd(threshold), BloomSignal.sigmaOf(spread))

    /**
     * The values [bloom] draws with.
     */
    public fun of(bloom: Bloom): BloomParams =
      at(bloom.intensity.toDouble(), bloom.threshold.toDouble(), bloom.spread.toDouble())
  }
}
