package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.internal.WIDE_FULL_SCALE

/**
 * Writes [FilmTone]'s fade as ffmpeg expressions.
 */
internal object FilmToneExpressions {
  /**
   * The `lutrgb` that draws [FilmTone.faded] at [params]' fade on `gbrap`, or on its sixteen bit planes when [wide].
   *
   * It stands in for [FilmExpressions.light] when no breath and no flicker run, since the fade reads no clock.
   */
  fun fade(
    params: FilmParams,
    wide: Boolean,
  ): FilterNode {
    val full = if (wide) WIDE_FULL_SCALE else FULL_SCALE
    val rounding = if (wide) "" else "0.5+"
    val faded = { channel: Int ->
      "clip(${rounding}val*${fadeScale(params, channel)}+${fadeLift(params, channel, full)},0,$full)"
    }
    return FilterNode("lutrgb", "r" to faded(RED), "g" to faded(GREEN), "b" to faded(BLUE))
  }

  /**
   * [FilmTone.fadeScale] of [channel] at [params]' fade.
   */
  fun fadeScale(
    params: FilmParams,
    channel: Int,
  ): Double = FilmTone.fadeScale(channel, params.fade)

  /**
   * [FilmTone.fadeLift] of [channel] at [params]' fade, in codes of a plane whose full scale is [full].
   */
  fun fadeLift(
    params: FilmParams,
    channel: Int,
    full: Int,
  ): Double = FilmTone.fadeLift(channel, params.fade) * full
}
