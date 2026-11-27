package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import kotlin.math.log2
import kotlin.math.pow

/**
 * Where [scale] sits on a size slider, which counts doublings of the scale. A step of one doubles it and a
 * step of minus one halves it.
 */
public fun sizeStepOf(scale: Float): Float =
  log2(scale.coerceIn(ComposedOverlayDefaults.MIN_SCALE, ComposedOverlayDefaults.MAX_SCALE))

/**
 * The scale the size slider at [step] asks for, held inside the bounds a pinch keeps to.
 */
public fun scaleOfStep(step: Float): Float =
  2f.pow(step).coerceIn(ComposedOverlayDefaults.MIN_SCALE, ComposedOverlayDefaults.MAX_SCALE)

/**
 * The range of the size slider, from the smallest scale a pinch allows to the largest.
 */
public val SIZE_STEPS: ClosedFloatingPointRange<Float> =
  log2(ComposedOverlayDefaults.MIN_SCALE)..log2(ComposedOverlayDefaults.MAX_SCALE)

/**
 * [degrees] as the same turn inside -180 to 180, the range of a turn slider. A turn already inside it comes
 * back unchanged.
 */
public fun turnOf(degrees: Float): Float {
  if (degrees in -HALF_TURN..HALF_TURN) return degrees
  val wrapped = degrees.mod(FULL_TURN)
  return if (wrapped > HALF_TURN) wrapped - FULL_TURN else wrapped
}

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
