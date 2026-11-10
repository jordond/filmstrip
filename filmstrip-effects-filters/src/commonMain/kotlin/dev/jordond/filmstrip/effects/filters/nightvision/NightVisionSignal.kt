package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import kotlin.math.exp

// The lift, a gain on the luma code that rolls off past the knee toward 1. Not measured, tuned on real footage.
internal const val NIGHT_VISION_GAIN = 1.15
internal const val NIGHT_VISION_KNEE = 0.8

// The grain's largest delta on the luma code, heavier than film's.
internal const val NIGHT_VISION_NOISE = 0.12

// The phosphor ramp, black at 0, a P43 style green at RAMP_MID and near white at 1, linear between the stops.
internal const val RAMP_MID = 0.6
internal val RAMP_MID_COLOUR = doubleArrayOf(0.14, 0.7, 0.12)
internal val RAMP_TOP_COLOUR = doubleArrayOf(0.82, 1.0, 0.74)

// The look's own vignette, in vignetteGain's terms.
internal const val NIGHT_VISION_VIGNETTE_STRENGTH = 0.6
internal const val NIGHT_VISION_VIGNETTE_RADIUS = 0.75
internal const val NIGHT_VISION_VIGNETTE_SOFTNESS = 0.35

/**
 * Every number the night vision look is made of, computed once here and consumed by all four lowerings.
 *
 * It all runs on SDR encoded codes. A pixel's luma takes the grain, goes through [phosphor] and is darkened by
 * [vignetteAt], and the result is mixed over the pixel at the look's intensity. The grain is film's, on film's raster
 * and clock, drawn from the look's own seed.
 */
@InternalFilmstripApi
public object NightVisionSignal {
  /**
   * The Y' code [luma] lifted by [params]' gain, linear up to the knee and rolling off toward 1 past it.
   *
   * [luma] is clamped to the code range first.
   */
  public fun lift(
    luma: Double,
    params: NightVisionParams,
  ): Double {
    val lifted = params.gain * luma.coerceIn(0.0, 1.0)
    if (lifted <= NIGHT_VISION_KNEE) return lifted
    val room = 1.0 - NIGHT_VISION_KNEE
    return NIGHT_VISION_KNEE + room * (1.0 - exp(-(lifted - NIGHT_VISION_KNEE) / room))
  }

  /**
   * The R'G'B' codes the phosphor ramp gives a [level] from `0.0` to `1.0`.
   */
  public fun ramp(level: Double): DoubleArray {
    val at = level.coerceIn(0.0, 1.0)
    return if (at <= RAMP_MID) {
      DoubleArray(RGB) { RAMP_MID_COLOUR[it] * at / RAMP_MID }
    } else {
      val t = (at - RAMP_MID) / (1.0 - RAMP_MID)
      DoubleArray(RGB) { RAMP_MID_COLOUR[it] + (RAMP_TOP_COLOUR[it] - RAMP_MID_COLOUR[it]) * t }
    }
  }

  /**
   * The R'G'B' codes a Y' code [luma] lands on, [lift] run through [ramp].
   */
  public fun phosphor(
    luma: Double,
    params: NightVisionParams,
  ): DoubleArray = ramp(lift(luma, params))

  /**
   * The grain added to the luma of [cell] at [tick] under [seed], from `-noise` to `noise` of [params].
   *
   * @param cell The cell on film's raster, from `FilmSignal.grainCell`.
   * @param tick The tick on film's clock, from `FilmSignal.grainTickAt`.
   */
  public fun noiseAt(
    seed: Int,
    tick: Int,
    cell: Int,
    params: NightVisionParams,
  ): Double = params.noise * FilmSignal.grainAt(cell, tick, seed)

  /**
   * What the look's vignette multiplies pixel [x], [y] of a frame [width] by [height] by, with [y] counted from the
   * top. The pixel is read at its centre.
   */
  public fun vignetteAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Double = vignetteAt((x + HALF) / width, (y + HALF) / height, width.toDouble() / height)

  /**
   * What the look's vignette multiplies a point [x], [y] by, each a fraction of the frame from its top left corner, on
   * a frame [aspect] times as wide as it is tall.
   */
  internal fun vignetteAt(
    x: Double,
    y: Double,
    aspect: Double,
  ): Double =
    vignetteGain(
      x = x,
      y = y,
      aspect = aspect,
      strength = NIGHT_VISION_VIGNETTE_STRENGTH,
      radius = NIGHT_VISION_VIGNETTE_RADIUS,
      softness = NIGHT_VISION_VIGNETTE_SOFTNESS,
    )

  /**
   * The R'G'B' a straight pixel of a frame ends on, a pure function a test holds a backend's pixels against.
   *
   * The grain of the pixel's cell at [tick] is added to its luma, that goes through [phosphor] and [vignetteAt], and
   * the result is mixed over [red], [green] and [blue] at [params]' intensity.
   *
   * @param x The pixel's column.
   * @param y The pixel's row, counted from the top.
   */
  public fun pixelAt(
    red: Double,
    green: Double,
    blue: Double,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    tick: Int,
    params: NightVisionParams,
  ): DoubleArray {
    val cell = FilmSignal.grainCell(x, y, width, height)
    val noisy = lumaOf(red, green, blue) + noiseAt(params.seed, tick, cell, params)
    val look = phosphor(noisy, params)
    val gain = vignetteAt(x, y, width, height)
    val original = doubleArrayOf(red, green, blue)
    return DoubleArray(RGB) { original[it] + params.intensity * (look[it] * gain - original[it]) }
  }
}

/**
 * The values one [NightVision] draws with, in code values.
 *
 * Built by [NightVisionParams.of], and read by every lowering so they all draw the same frame. The ramp and the
 * vignette stay fixed, so only the intensity and the seed change with the controls.
 *
 * @property intensity How much of the look is mixed over the frame.
 * @property seed The seed the grain is drawn from.
 * @property gain What the luma code is multiplied by before the knee.
 * @property noise The largest grain delta on the luma code.
 */
@InternalFilmstripApi
public class NightVisionParams private constructor(
  public val intensity: Double,
  public val seed: Int,
  public val gain: Double,
  public val noise: Double,
) {
  /**
   * Builds the values a [NightVision] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [NightVision] at [intensity] with [seed] draws with.
     */
    public fun at(
      intensity: Double,
      seed: Int = NightVision.DEFAULT_SEED,
    ): NightVisionParams = NightVisionParams(intensity, seed, NIGHT_VISION_GAIN, NIGHT_VISION_NOISE)

    /**
     * The values [nightVision] draws with.
     */
    public fun of(nightVision: NightVision): NightVisionParams = at(nightVision.intensity.toDouble(), nightVision.seed)
  }
}

private const val RGB = 3
private const val HALF = 0.5
