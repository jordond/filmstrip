package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

/**
 * Computes the region this crop keeps, as a fraction of the frame entering it.
 *
 * [Fit.Contain] and [Fit.Stretch] do not remove pixels, they letterbox or distort at the size stage
 * instead.
 *
 * @return The retained rectangle, or [NormalizedRect.Full] when nothing is cropped away.
 */
public fun Crop.retainedRect(inputSize: Size): NormalizedRect {
  if (fit != Fit.Crop) return NormalizedRect.Full
  if (inputSize.width <= 0 || inputSize.height <= 0) return NormalizedRect.Full

  val inputAspect = inputSize.aspect
  val targetAspect = aspect.value
  if (targetAspect <= 0f || inputAspect <= 0f) return NormalizedRect.Full

  return if (inputAspect > targetAspect) {
    val width = targetAspect / inputAspect
    val left = ((1f - width) * anchor.x).coerceIn(0f, 1f - width)
    NormalizedRect(left = left, top = 0f, right = left + width, bottom = 1f)
  } else {
    val height = inputAspect / targetAspect
    val top = ((1f - height) * anchor.y).coerceIn(0f, 1f - height)
    NormalizedRect(left = 0f, top = top, right = 1f, bottom = top + height)
  }
}

/**
 * Applies this rectangle to [inputSize].
 *
 * @return The frame size the crop produces, rounded down to whole pixels and never smaller than
 * 1x1.
 */
public fun NormalizedRect.applyTo(inputSize: Size): Size =
  Size(
    width = (inputSize.width * width).toInt().coerceAtLeast(1),
    height = (inputSize.height * height).toInt().coerceAtLeast(1),
  )
