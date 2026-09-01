package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
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

private const val HALF_TURN_DEGREES = 180
