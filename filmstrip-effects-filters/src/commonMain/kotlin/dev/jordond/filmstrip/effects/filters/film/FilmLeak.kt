package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.smoothstep
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// A spot of light lives for one run of LEAK_RUN_TICKS ticks, and LEAK_SPOTS of them run at once, each a share of a run
// out of step with the one before it.
internal const val LEAK_RUN_TICKS = 96
internal const val LEAK_SPOTS = 2
internal const val LEAK_STAGGER_TICKS = LEAK_RUN_TICKS / LEAK_SPOTS

// The light at a spot's centre at a leak of 1 with its envelope open, before its tint.
internal const val LEAK_LEVEL = 1.0

// A spot's radius, in frame heights.
internal const val LEAK_RADIUS_MIN = 0.7
internal const val LEAK_RADIUS_MAX = 1.3

// A spot sits on the left edge, the right edge or the top.
internal const val LEAK_EDGES = 3
internal const val LEAK_EDGE_LEFT = 0
internal const val LEAK_EDGE_RIGHT = 1

internal const val PASS_LEAK_EDGE = 14
internal const val PASS_LEAK_PLACE = 15
internal const val PASS_LEAK_RADIUS = 16
internal const val PASS_LEAK_TINT = 17

// The warm tints a spot draws from, each as red, green and blue.
internal val LEAK_TINTS: List<DoubleArray> =
  listOf(
    doubleArrayOf(1.0, 0.45, 0.12),
    doubleArrayOf(1.0, 0.25, 0.1),
    doubleArrayOf(1.0, 0.7, 0.3),
    doubleArrayOf(1.0, 0.35, 0.4),
  )

private const val RGB = 3

/**
 * The light leaks of a film look, computed once here and consumed by all four lowerings.
 *
 * Two spots of warm light sit on the frame's edges at any time. Each lives for a run of 96 ticks, four seconds, opens
 * and closes on nothing over it, and draws a new edge, place, radius and tint for its next run. The second spot runs
 * half a run out of step with the first. The ticks are [FilmSignal.grainTickAt]'s.
 */
@InternalFilmstripApi
public object FilmLeak {
  /**
   * The run of 96 ticks that [tick] sits in.
   */
  public fun leakRunAt(tick: Int): Int = floor(tick.toDouble() / LEAK_RUN_TICKS).toInt()

  /**
   * How far open a spot is at [tick] of its own clock, from 0 on its run's first tick up to 1 half way through and
   * back down.
   */
  public fun leakEnvelope(tick: Int): Double {
    val phase = (tick - leakRunAt(tick) * LEAK_RUN_TICKS).toDouble() / LEAK_RUN_TICKS
    val wave = sin(PI * phase)
    return wave * wave
  }

  /**
   * The spots lit at [tick] under [seed], each with what its run drew.
   */
  public fun leakSpots(
    seed: Int,
    tick: Int,
  ): List<LeakSpot> = List(LEAK_SPOTS) { index -> spotAt(seed, tick + index * LEAK_STAGGER_TICKS, index) }

  /**
   * How much of a spot's light reaches a pixel [distance] from its centre, in frame heights.
   *
   * @return 1 at the centre, falling along a squared smoothstep to exactly 0 at [radius] and past it.
   */
  public fun leakFalloff(
    distance: Double,
    radius: Double,
  ): Double {
    val fall = 1.0 - smoothstep(0.0, radius, distance)
    return fall * fall
  }

  /**
   * The light the leaks add at [x], [y], as fractions of the frame with [y] from the top, at [tick] under [seed], at
   * a [leak] from 0 to 1.
   *
   * Each spot gives its tint times its envelope and its falloff. The spots sum, and each channel holds at 1.
   *
   * @param aspect The frame's width over its height.
   * @return The light as red, green and blue.
   */
  public fun leakAt(
    x: Double,
    y: Double,
    aspect: Double,
    tick: Int,
    seed: Int,
    leak: Double,
  ): DoubleArray {
    val light = DoubleArray(RGB)
    if (leak <= 0.0) return light
    for (spot in leakSpots(seed, tick)) {
      val across = (x - spot.x) * aspect
      val down = y - spot.y
      val weight =
        LEAK_LEVEL * leak * spot.envelope * leakFalloff(sqrt(across * across + down * down), spot.radius)
      for (channel in 0 until RGB) light[channel] += weight * spot.tint[channel]
    }
    return DoubleArray(RGB) { min(1.0, light[it]) }
  }

  /**
   * [red], [green] and [blue] with [light] screened over them, which brightens a dark code more than a bright one and
   * never passes 1.
   */
  public fun leaked(
    red: Double,
    green: Double,
    blue: Double,
    light: DoubleArray,
  ): DoubleArray =
    doubleArrayOf(
      red + light[0] * (1.0 - red),
      green + light[1] * (1.0 - green),
      blue + light[2] * (1.0 - blue),
    )

  private fun spotAt(
    seed: Int,
    clock: Int,
    index: Int,
  ): LeakSpot {
    val run = leakRunAt(clock)
    val draw = { pass: Int -> FilterNoise.unorm(FilterNoise.hash24(seed, pass, run, index)) }
    val edge = floor(draw(PASS_LEAK_EDGE) * LEAK_EDGES).toInt()
    val place = draw(PASS_LEAK_PLACE)
    val tint = LEAK_TINTS[floor(draw(PASS_LEAK_TINT) * LEAK_TINTS.size).toInt()]
    return LeakSpot(
      x =
        when (edge) {
          LEAK_EDGE_LEFT -> 0.0
          LEAK_EDGE_RIGHT -> 1.0
          else -> place
        },
      y = if (edge == LEAK_EDGE_LEFT || edge == LEAK_EDGE_RIGHT) place else 0.0,
      radius = LEAK_RADIUS_MIN + (LEAK_RADIUS_MAX - LEAK_RADIUS_MIN) * draw(PASS_LEAK_RADIUS),
      tint = tint,
      envelope = leakEnvelope(clock),
    )
  }
}

/**
 * One spot of leaked light at one tick.
 *
 * @property x Where its centre sits across the frame, as a fraction of its width.
 * @property y Where its centre sits down the frame, as a fraction of its height.
 * @property radius How far its light reaches, in frame heights.
 * @property tint Its colour, as red, green and blue.
 * @property envelope How far open it is at this tick, from 0 to 1.
 */
@InternalFilmstripApi
public class LeakSpot internal constructor(
  public val x: Double,
  public val y: Double,
  public val radius: Double,
  public val tint: DoubleArray,
  public val envelope: Double,
)
