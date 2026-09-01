package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.Crop
import dev.jordond.filmstrip.effects.CropRect
import dev.jordond.filmstrip.effects.Flip
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.effects.Scale
import dev.jordond.filmstrip.effects.applyTo
import dev.jordond.filmstrip.effects.retainedRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt

// The frame a chain of geometry effects leaves behind, derived once. Every backend has to agree on
// the frame a clip's own effects hand to the composition's, and on the frame the composition's own
// geometry hands to everything after it, so that arithmetic lives here rather than in four backends
// each doing their own.

/**
 * The frame [spec] produces out of a frame of [size].
 *
 * A rotate that turns the frame on its side swaps its two sides, a crop keeps only the region
 * [retainedRect] or its own rect describes, and a scale sets the height and follows the frame's own
 * aspect for the width. Every other spec, including one this pipeline does not know, leaves the
 * frame alone.
 */
@InternalFilmstripApi
public fun frameAfter(
  spec: EffectSpec,
  size: Size,
): Size =
  when (spec) {
    is Rotate -> {
      if (spec.degrees % HALF_TURN_DEGREES == 0) size else Size(size.height, size.width)
    }
    is Flip -> {
      size
    }
    is CropRect -> {
      spec.rect.applyTo(size)
    }
    is Crop -> {
      if (spec.fit == Fit.Crop) {
        spec.retainedRect(size).applyTo(size)
      } else {
        Size((size.height * spec.aspect.value).roundToInt().coerceAtLeast(1), size.height)
      }
    }
    is Scale -> {
      Size((spec.targetHeight * size.aspect).roundToInt().coerceAtLeast(1), spec.targetHeight)
    }
    else -> {
      size
    }
  }

/**
 * The frame that comes out of [sourceSize] once every stage in [stages] has run.
 *
 * Each stage is sorted into canonical order on its own, and the stages themselves run in the order
 * given. A clip's own geometry always finishes before the composition's starts, so folding the
 * stages separately rather than sorting every spec together is what keeps this the frame a backend
 * actually draws against.
 */
@InternalFilmstripApi
public fun frameThrough(
  sourceSize: Size,
  stages: List<List<EffectSpec>>,
): Size = stages.flatMap { it.inCanonicalOrder() }.fold(sourceSize) { size, spec -> frameAfter(spec, size) }

/**
 * The largest frame a composition that takes its frame from a still asks for.
 *
 * A still's pixel bounds come off a camera sensor rather than out of an encoder, and a phone photo
 * is several times the frame consumer video pipelines are built around. An encoder that publishes
 * its limits as one range per side accepts such a frame on paper and then spends minutes on it or
 * runs the device out of memory, so a composition with no video clip to measure from is held here
 * as well as to the encoder's own ceiling.
 *
 * A composition that has a video clip takes its frame from that clip and never reaches this, and an
 * [ExportSpec.targetHeight] names the frame outright, which is how a caller asks for a larger one.
 */
@InternalFilmstripApi
public val MAX_STILL_FRAME: Size = Size(3840, 2160)

/**
 * The largest frame with [size]'s aspect that fits inside [bounds], never larger than [size].
 *
 * Both sides scale by the same factor, so the frame keeps its shape. Coercing each side on its own
 * would land a 4:3 frame on a 16:9 ceiling as a 16:9 one, which changes the picture rather than its
 * size. A frame that already fits comes back untouched.
 */
@InternalFilmstripApi
public fun frameWithin(
  size: Size,
  bounds: Size,
): Size {
  if (size.width <= 0 || size.height <= 0 || bounds.width <= 0 || bounds.height <= 0) return size
  if (size.width <= bounds.width && size.height <= bounds.height) return size

  val scale =
    minOf(
      bounds.width.toFloat() / size.width.toFloat(),
      bounds.height.toFloat() / size.height.toFloat(),
    )
  return Size(
    (size.width * scale).roundToInt().coerceIn(1, bounds.width),
    (size.height * scale).roundToInt().coerceIn(1, bounds.height),
  )
}

private const val HALF_TURN_DEGREES = 180
