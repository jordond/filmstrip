package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// The frame a chain of geometry effects leaves behind, derived once. Every backend has to agree on
// the frame a clip's own effects hand to the composition's, and on the frame the composition's own
// geometry hands to everything after it, so that arithmetic lives here and every backend reads it.

/**
 * A frame's size and the aspect that size stands for.
 *
 * The aspect is the frame's own width over height until a [Crop] sets it to the crop's aspect, which the frame's whole
 * pixel size only rounds. A [Scale] reads it, so the frame it makes is the same whatever
 * height the canvas was rounded at.
 *
 * @property size The frame in whole pixels.
 * @property aspect The width over height [size] is a rounding of.
 */
@InternalFilmstripApi
public class MeasuredFrame(
  public val size: Size,
  public val aspect: Float = size.aspect,
)

/**
 * The frame [spec] produces out of a frame of [size].
 *
 * @see frameAfter
 */
@InternalFilmstripApi
public fun frameAfter(
  spec: EffectSpec,
  size: Size,
): Size = frameAfter(spec, MeasuredFrame(size)).size

/**
 * The frame [spec] produces out of [frame].
 *
 * A rotate lands on the frame [turnedFrame] answers, and its aspect comes from the exact aspect, not from the rounded
 * pixels. A crop keeps only the region [retainedRect] or its own rect describes, a crop rect keeping its share of the
 * exact aspect, and a scale sets the height and follows the frame's own aspect for the width. A crop, whatever its
 * fit, gives the frame the aspect it was asked for, and a quarter turn turns that aspect over. Every other spec,
 * including one this pipeline does not know, leaves the frame alone.
 */
@InternalFilmstripApi
public fun frameAfter(
  spec: EffectSpec,
  frame: MeasuredFrame,
): MeasuredFrame {
  val size = frame.size
  return when (spec) {
    is Rotate -> {
      val turned = spec.turnedFrame(size)
      MeasuredFrame(
        turned,
        when {
          spec.degrees % HALF_TURN == 0 -> frame.aspect
          spec.degrees % QUARTER_TURN == 0 -> 1f / frame.aspect
          else -> turnedAspect(frame.aspect, spec.degrees)
        },
      )
    }
    is Flip -> {
      frame
    }
    is CropRect -> {
      MeasuredFrame(spec.rect.applyTo(size), frame.aspect * spec.rect.width / spec.rect.height)
    }
    is Crop -> {
      if (spec.fit == Fit.Crop) {
        MeasuredFrame(spec.retainedRect(size).applyTo(size), spec.aspect.value)
      } else {
        MeasuredFrame(
          Size((size.height * spec.aspect.value).roundToInt().coerceAtLeast(1), size.height),
          spec.aspect.value,
        )
      }
    }
    is Scale -> {
      MeasuredFrame(Size((spec.heightPx * frame.aspect).roundToInt().coerceAtLeast(1), spec.heightPx), frame.aspect)
    }
    else -> {
      frame
    }
  }
}

/**
 * The aspect of the bounding box of a picture of [aspect] turned by a free angle, from the exact aspect so the pixel
 * rounding [turnedFrame] does is not carried into it.
 */
private fun turnedAspect(
  aspect: Float,
  degrees: Int,
): Float {
  val radians = degrees * PI / HALF_TURN
  val cos = abs(cos(radians))
  val sin = abs(sin(radians))
  return ((aspect * cos + sin) / (aspect * sin + cos)).toFloat()
}

private const val HALF_TURN = 180
private const val QUARTER_TURN = 90

/**
 * The frame that comes out of [sourceSize] once every stage in [stages] has run.
 *
 * Each stage is sorted into canonical order on its own, and the stages themselves run in the order
 * given. A clip's own geometry always finishes before the composition's starts, so folding each
 * stage on its own is what keeps this the frame a backend actually draws against.
 */
internal fun frameThrough(
  sourceSize: Size,
  stages: List<List<EffectSpec>>,
): Size = measuredThrough(MeasuredFrame(sourceSize), stages).size

/**
 * [frameThrough] from a [source] that already holds an aspect, which the frame it ends on keeps.
 */
internal fun measuredThrough(
  source: MeasuredFrame,
  stages: List<List<EffectSpec>>,
): MeasuredFrame = stages.flatMap { canonicalOrder(it) }.fold(source) { frame, spec -> frameAfter(spec, frame) }
