package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi

// The print's fade at a fade of 1, for red, green and blue. A code c lands on lift + c * (1 - span), so black rises to
// the lift and white falls by the span less the lift. No lift is past its span, which holds every code inside 0 to 1.
internal val FADE_LIFT = doubleArrayOf(0.18, 0.06, 0.09)
internal val FADE_SPAN = doubleArrayOf(0.20, 0.26, 0.30)

// The breath. A knot every BREATH_KNOT_TICKS ticks holds three random draws, and the ticks between ease from one knot
// to the next. The depths are the largest change at a breath of 1: of contrast around mid grey, of red against blue,
// and of green.
internal const val BREATH_KNOT_TICKS = 12
internal const val BREATH_CONTRAST = 0.12
internal const val BREATH_WARMTH = 0.08
internal const val BREATH_TINT = 0.04
internal const val BREATH_PIVOT = 0.5
internal const val PASS_BREATH_CONTRAST = 18
internal const val PASS_BREATH_WARMTH = 19
internal const val PASS_BREATH_TINT = 20

/**
 * The colour a faded, breathing print gives a frame, computed once here and consumed by all four lowerings.
 *
 * It runs on SDR encoded codes, after the leak and before the flicker. The fade holds still, and the breath changes
 * with the frame's tick.
 */
@InternalFilmstripApi
public object FilmTone {
  /**
   * How many ticks sit between two of the breath's knots.
   *
   * A frame on a multiple of it reads one knot's draws alone, and every other frame a mix of two.
   */
  public const val KNOT_TICKS: Int = BREATH_KNOT_TICKS

  /**
   * What a [fade] from 0 to 1 multiplies the code of [channel] by, with red 0, green 1 and blue 2.
   */
  public fun fadeScale(
    channel: Int,
    fade: Double,
  ): Double = 1.0 - fade * FADE_SPAN[channel]

  /**
   * What a [fade] from 0 to 1 adds to the code of [channel] after [fadeScale], with red 0, green 1 and blue 2.
   */
  public fun fadeLift(
    channel: Int,
    fade: Double,
  ): Double = fade * FADE_LIFT[channel]

  /**
   * [red], [green] and [blue] the way a print faded by [fade] shows them.
   *
   * Blacks lift toward red and whites fall toward it, the cast a print takes as its cyan dye goes, and the contrast
   * drops with them. Every code stays inside 0 to 1.
   */
  public fun faded(
    red: Double,
    green: Double,
    blue: Double,
    fade: Double,
  ): DoubleArray {
    val codes = doubleArrayOf(red, green, blue)
    return DoubleArray(codes.size) { codes[it] * fadeScale(it, fade) + fadeLift(it, fade) }
  }

  /**
   * How the frame at [tick] breathes under [seed], at a [breath] from 0 to 1.
   *
   * A knot every twelve ticks holds a draw for the contrast, one for the warmth and one for the tint, and the ticks
   * between ease from one knot to the next, so the colour drifts rather than jumps. The warmth raises red as far as it
   * lowers blue.
   *
   * @return The contrast, then the gain of red, green and blue, all 1 at a [breath] of 0.
   */
  public fun breathAt(
    seed: Int,
    tick: Int,
    breath: Double,
  ): DoubleArray {
    val knot = FilmSignal.floorDiv(tick, BREATH_KNOT_TICKS)
    val f = (tick - knot * BREATH_KNOT_TICKS).toDouble() / BREATH_KNOT_TICKS
    val ease = f * f * (3.0 - 2.0 * f)
    val warmth = BREATH_WARMTH * breath * FilmSignal.knotted(seed, PASS_BREATH_WARMTH, knot, ease)
    return doubleArrayOf(
      1.0 + BREATH_CONTRAST * breath * FilmSignal.knotted(seed, PASS_BREATH_CONTRAST, knot, ease),
      1.0 + warmth,
      1.0 + BREATH_TINT * breath * FilmSignal.knotted(seed, PASS_BREATH_TINT, knot, ease),
      1.0 - warmth,
    )
  }

  /**
   * [red], [green] and [blue] under [breath], the contrast and gains [breathAt] gives.
   *
   * The contrast turns each code around mid grey and its gain follows, written so a contrast and a gain of 1 hand
   * the code back exactly. Nothing is clamped here.
   */
  public fun breathed(
    red: Double,
    green: Double,
    blue: Double,
    breath: DoubleArray,
  ): DoubleArray {
    val codes = doubleArrayOf(red, green, blue)
    return DoubleArray(codes.size) { (codes[it] * breath[0] + BREATH_PIVOT * (1.0 - breath[0])) * breath[it + 1] }
  }

  /**
   * [red], [green] and [blue] after [faded] and then [breathed] at [tick], with [params]' fade, breath and seed.
   *
   * A code can leave 0 to 1 here, and [FilmSignal.pixelAt] clamps it once the flicker has run.
   */
  public fun toned(
    red: Double,
    green: Double,
    blue: Double,
    tick: Int,
    params: FilmParams,
  ): DoubleArray {
    val print = faded(red, green, blue, params.fade)
    return breathed(print[0], print[1], print[2], breathAt(params.seed, tick, params.breath))
  }
}
