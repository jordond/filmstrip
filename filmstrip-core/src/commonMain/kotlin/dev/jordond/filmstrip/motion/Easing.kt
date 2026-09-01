package dev.jordond.filmstrip.motion

import kotlinx.serialization.Serializable

/**
 * How a value is paced between where it starts and where it ends.
 *
 * The curves are quadratic, and every one of them passes through 0 at the start and 1 at the end.
 * What separates them is the middle, so an effect reading this is only paced differently between
 * its two ends.
 */
@Serializable
public enum class Easing {
  /**
   * Constant rate from end to end.
   */
  Linear,

  /**
   * Starts still and accelerates into the end.
   */
  EaseIn,

  /**
   * Starts at speed and decelerates into the end.
   */
  EaseOut,

  /**
   * Accelerates out of the start and decelerates into the end, symmetric about the middle.
   */
  EaseInOut,
}

/**
 * Paces [progress] along this curve.
 *
 * Every backend that draws a paced effect reads its position from here, so the same declaration
 * lands at the same point of its travel whichever engine is drawing.
 *
 * @param progress How far through the travel, clamped to `0f..1f`.
 * @return The eased fraction of the travel, in `0f..1f`.
 */
public fun Easing.paced(progress: Float): Float {
  val t = progress.coerceIn(0f, 1f)
  return when (this) {
    Easing.Linear -> t
    Easing.EaseIn -> t * t
    Easing.EaseOut -> t * (2f - t)
    Easing.EaseInOut -> if (t < HALF) 2f * t * t else (FOUR - 2f * t) * t - 1f
  }
}

private const val HALF = 0.5f
private const val FOUR = 4f
