package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.compose.ui.CropOverlayDefaults
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.NormalizedRect

/**
 * What a crop gesture is allowed to produce.
 *
 * Immutable, so an overlay holding one as an option stays skippable.
 */
@Immutable
public sealed interface CropConstraint {
  /**
   * Corrects [proposed] into a rectangle this constraint allows.
   *
   * @param proposed What the gesture asked for. The edges [handle] did not touch carry the value they already had.
   * @param handle Which part of the rectangle moved.
   * @return The corrected rectangle.
   */
  public fun constrain(
    proposed: NormalizedRect,
    handle: CropHandle,
  ): NormalizedRect

  /**
   * A rectangle of any size at or above [minWidth] and [minHeight].
   *
   * @property minWidth How narrow the rectangle may get, as a fraction of the frame.
   * @property minHeight How short the rectangle may get, as a fraction of the frame.
   */
  @Poko
  public class Free(
    public val minWidth: Float,
    public val minHeight: Float,
  ) : CropConstraint {
    override fun constrain(
      proposed: NormalizedRect,
      handle: CropHandle,
    ): NormalizedRect {
      val minW = minWidth.coerceIn(0f, 1f)
      val minH = minHeight.coerceIn(0f, 1f)
      val maxLeft = (proposed.right - minW).coerceAtLeast(0f)
      val minRight = (proposed.left + minW).coerceAtMost(1f)
      val maxTop = (proposed.bottom - minH).coerceAtLeast(0f)
      val minBottom = (proposed.top + minH).coerceAtMost(1f)

      return when (handle) {
        CropHandle.Body -> {
          proposed.clampedTranslation()
        }
        CropHandle.TopLeft -> {
          NormalizedRect(
            proposed.left.coerceIn(0f, maxLeft),
            proposed.top.coerceIn(0f, maxTop),
            proposed.right,
            proposed.bottom,
          )
        }
        CropHandle.Top -> {
          NormalizedRect(proposed.left, proposed.top.coerceIn(0f, maxTop), proposed.right, proposed.bottom)
        }
        CropHandle.TopRight -> {
          NormalizedRect(
            proposed.left,
            proposed.top.coerceIn(0f, maxTop),
            proposed.right.coerceIn(minRight, 1f),
            proposed.bottom,
          )
        }
        CropHandle.Right -> {
          NormalizedRect(proposed.left, proposed.top, proposed.right.coerceIn(minRight, 1f), proposed.bottom)
        }
        CropHandle.BottomRight -> {
          NormalizedRect(
            proposed.left,
            proposed.top,
            proposed.right.coerceIn(minRight, 1f),
            proposed.bottom.coerceIn(minBottom, 1f),
          )
        }
        CropHandle.Bottom -> {
          NormalizedRect(proposed.left, proposed.top, proposed.right, proposed.bottom.coerceIn(minBottom, 1f))
        }
        CropHandle.BottomLeft -> {
          NormalizedRect(
            proposed.left.coerceIn(0f, maxLeft),
            proposed.top,
            proposed.right,
            proposed.bottom.coerceIn(minBottom, 1f),
          )
        }
        CropHandle.Left -> {
          NormalizedRect(proposed.left.coerceIn(0f, maxLeft), proposed.top, proposed.right, proposed.bottom)
        }
      }
    }
  }

  /**
   * A rectangle whose width over height always equals [ratio].
   *
   * [ratio] is a fraction of the frame on both axes, not of the frame's own aspect. Build one from a picture ratio
   * with [lockedTo], which is where that conversion lives.
   *
   * A corner drag sets the rectangle's size from its horizontal movement alone, anchored at the opposite corner, so
   * vertical movement on a corner does nothing. An edge drag holds the opposite edge on that same axis fixed and grows
   * the other axis symmetrically around its own centre.
   *
   * @property ratio Width over height the rectangle always keeps, in frame-fraction space.
   * @property minWidth How narrow the rectangle may get, as a fraction of the frame.
   */
  @Poko
  public class FixedAspect(
    public val ratio: Float,
    public val minWidth: Float,
  ) : CropConstraint {
    override fun constrain(
      proposed: NormalizedRect,
      handle: CropHandle,
    ): NormalizedRect =
      when (handle) {
        CropHandle.Body -> {
          proposed.clampedTranslation()
        }
        CropHandle.TopLeft -> {
          corner(
            anchorX = proposed.right,
            anchorY = proposed.bottom,
            proposedX = proposed.left,
            freeIsRight = false,
            freeIsBottom = false,
          )
        }
        CropHandle.TopRight -> {
          corner(
            anchorX = proposed.left,
            anchorY = proposed.bottom,
            proposedX = proposed.right,
            freeIsRight = true,
            freeIsBottom = false,
          )
        }
        CropHandle.BottomRight -> {
          corner(
            anchorX = proposed.left,
            anchorY = proposed.top,
            proposedX = proposed.right,
            freeIsRight = true,
            freeIsBottom = true,
          )
        }
        CropHandle.BottomLeft -> {
          corner(
            anchorX = proposed.right,
            anchorY = proposed.top,
            proposedX = proposed.left,
            freeIsRight = false,
            freeIsBottom = true,
          )
        }
        CropHandle.Right -> {
          horizontalEdge(proposed.left, (proposed.top + proposed.bottom) / 2f, proposed.right, freeIsRight = true)
        }
        CropHandle.Left -> {
          horizontalEdge(proposed.right, (proposed.top + proposed.bottom) / 2f, proposed.left, freeIsRight = false)
        }
        CropHandle.Bottom -> {
          verticalEdge(proposed.top, (proposed.left + proposed.right) / 2f, proposed.bottom, freeIsBottom = true)
        }
        CropHandle.Top -> {
          verticalEdge(proposed.bottom, (proposed.left + proposed.right) / 2f, proposed.top, freeIsBottom = false)
        }
      }

    /**
     * A rectangle sized from a drag to [proposedX], anchored at ([anchorX], [anchorY]) which stays exactly fixed.
     */
    private fun corner(
      anchorX: Float,
      anchorY: Float,
      proposedX: Float,
      freeIsRight: Boolean,
      freeIsBottom: Boolean,
    ): NormalizedRect {
      val minW = minWidth.coerceIn(0f, 1f)
      val maxWidthX = if (freeIsRight) 1f - anchorX else anchorX
      val maxHeightY = if (freeIsBottom) 1f - anchorY else anchorY
      val maxWidth = minOf(maxWidthX, maxHeightY * ratio).coerceAtLeast(0f)
      val clampedMinW = minW.coerceAtMost(maxWidth)

      val rawWidth = if (freeIsRight) proposedX - anchorX else anchorX - proposedX
      val width = rawWidth.coerceIn(clampedMinW, maxWidth)
      val height = width / ratio

      val freeX = if (freeIsRight) anchorX + width else anchorX - width
      val freeY = if (freeIsBottom) anchorY + height else anchorY - height

      return NormalizedRect(
        left = minOf(anchorX, freeX),
        top = minOf(anchorY, freeY),
        right = maxOf(anchorX, freeX),
        bottom = maxOf(anchorY, freeY),
      )
    }

    /**
     * A rectangle resized horizontally from [fixedX], with the vertical axis kept centred on [centerY].
     */
    private fun horizontalEdge(
      fixedX: Float,
      centerY: Float,
      proposedX: Float,
      freeIsRight: Boolean,
    ): NormalizedRect {
      val minW = minWidth.coerceIn(0f, 1f)
      val maxWidthX = if (freeIsRight) 1f - fixedX else fixedX
      val maxHeightY = minOf(centerY, 1f - centerY) * 2f
      val maxWidth = minOf(maxWidthX, maxHeightY * ratio).coerceAtLeast(0f)
      val clampedMinW = minW.coerceAtMost(maxWidth)

      val rawWidth = if (freeIsRight) proposedX - fixedX else fixedX - proposedX
      val width = rawWidth.coerceIn(clampedMinW, maxWidth)
      val height = width / ratio

      val freeX = if (freeIsRight) fixedX + width else fixedX - width
      return NormalizedRect(
        left = minOf(fixedX, freeX),
        top = centerY - height / 2f,
        right = maxOf(fixedX, freeX),
        bottom = centerY + height / 2f,
      )
    }

    /**
     * A rectangle resized vertically from [fixedY], with the horizontal axis kept centred on [centerX].
     */
    private fun verticalEdge(
      fixedY: Float,
      centerX: Float,
      proposedY: Float,
      freeIsBottom: Boolean,
    ): NormalizedRect {
      val minW = minWidth.coerceIn(0f, 1f)
      val maxHeightY = if (freeIsBottom) 1f - fixedY else fixedY
      val maxWidthX = minOf(centerX, 1f - centerX) * 2f
      val maxHeight = minOf(maxHeightY, maxWidthX / ratio).coerceAtLeast(0f)
      val minH = (minW / ratio).coerceAtMost(maxHeight)

      val rawHeight = if (freeIsBottom) proposedY - fixedY else fixedY - proposedY
      val height = rawHeight.coerceIn(minH, maxHeight)
      val width = height * ratio

      val freeY = if (freeIsBottom) fixedY + height else fixedY - height
      return NormalizedRect(
        left = centerX - width / 2f,
        top = minOf(fixedY, freeY),
        right = centerX + width / 2f,
        bottom = maxOf(fixedY, freeY),
      )
    }
  }

  public companion object {
    /**
     * A [FixedAspect] holding [aspect] as a picture ratio, on a frame of [frameAspect].
     *
     * [FixedAspect.ratio] measures the frame on both axes rather than the picture, so a picture ratio is divided by
     * the frame's own aspect on the way in. A square crop on a 16:9 frame is a rectangle nine sixteenths as wide as it
     * is tall in frame fractions, and this is the only place that division happens.
     *
     * A [frameAspect] at or below zero has no shape to divide by and answers [CropOverlayDefaults.Constraint].
     *
     * @param aspect The picture ratio the rectangle holds.
     * @param frameAspect Width over height of the frame the rectangle is drawn on, which is the frame the overlay
     * letterboxed the video into.
     * @param minWidth How narrow the rectangle may get, as a fraction of the frame.
     * @return What the gesture may produce.
     */
    public fun lockedTo(
      aspect: AspectRatio,
      frameAspect: Float,
      minWidth: Float = CropOverlayDefaults.MinWidth,
    ): CropConstraint {
      if (frameAspect <= 0f) return CropOverlayDefaults.Constraint

      return FixedAspect(ratio = aspect.value / frameAspect, minWidth = minWidth)
    }
  }
}

/**
 * [this] moved so it lies inside `0f..1f` , without changing its width or height.
 */
private fun NormalizedRect.clampedTranslation(): NormalizedRect {
  val newLeft = left.coerceIn(0f, (1f - width).coerceAtLeast(0f))
  val newTop = top.coerceIn(0f, (1f - height).coerceAtLeast(0f))
  return NormalizedRect(newLeft, newTop, newLeft + width, newTop + height)
}
