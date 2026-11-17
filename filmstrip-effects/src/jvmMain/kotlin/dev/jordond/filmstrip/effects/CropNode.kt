package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.exactCrop
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

/**
 * The `crop` node that cuts this rectangle out of a frame of [inputSize].
 *
 * The size is [applyTo], the one the planner reports for the same crop. The position is the rectangle's corner rounded
 * down to a whole pixel.
 */
@InternalFilmstripApi
public fun NormalizedRect.cropNode(inputSize: Size): FilterNode {
  val size = applyTo(inputSize)
  return exactCrop(
    width = size.width,
    height = size.height,
    x = (inputSize.width * left).toInt(),
    y = (inputSize.height * top).toInt(),
  )
}
