package dev.jordond.filmstrip.effects.overlay

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.abs
import kotlin.math.min

/**
 * A corner of the frame and how far in from it an overlay sits.
 *
 * @property corner Which corner the overlay is measured from.
 * @property margin Inset from that corner, as a fraction of the frame's shorter side.
 */
@Poko
@Immutable
public class CornerInset(
  public val corner: Corner,
  public val margin: Float,
)

/**
 * The corner and margin that bring an image overlay's own corner to this point.
 *
 * The inverse of what [ImageOverlay.placedOn] does with [ImageOverlay.corner] and
 * [ImageOverlay.margin], for a caller holding a position a drag produced. The corner is the
 * quadrant of the frame the point falls in, and the margin is read back off the two insets from it.
 * One margin answers for both axes, so a point off the diagonal the forward arithmetic reaches
 * takes the margin midway between the two the insets imply.
 *
 * Only the frame's proportions are read, so a measurement in view pixels answers the same as one in
 * output pixels. A point far enough past the middle that the forward arithmetic would hold the
 * margin at half the frame does not come back as the margin that produced it.
 *
 * @param frame The frame the point is measured on, in pixels.
 * @return The corner the point sits nearest and the margin that reaches it.
 */
public fun Anchor.nearestCornerInset(frame: Size): CornerInset {
  val corner =
    when {
      x < HALF -> if (y < HALF) Corner.TopStart else Corner.BottomStart
      else -> if (y < HALF) Corner.TopEnd else Corner.BottomEnd
    }
  val shorter = min(frame.width, frame.height)
  if (shorter <= 0) return CornerInset(corner, 0f)

  val at = corner.anchor()
  val insetX = abs(x - at.x) * frame.width
  val insetY = abs(y - at.y) * frame.height

  return CornerInset(corner, (insetX + insetY) / (2f * shorter))
}

internal fun Corner.anchor(): Anchor =
  when (this) {
    Corner.TopStart -> Anchor.TopStart
    Corner.TopEnd -> Anchor.TopEnd
    Corner.BottomStart -> Anchor.BottomStart
    Corner.BottomEnd -> Anchor.BottomEnd
  }

internal fun Corner.inset(
  x: Float,
  y: Float,
): Anchor =
  when (this) {
    Corner.TopStart -> Anchor(x, y)
    Corner.TopEnd -> Anchor(1f - x, y)
    Corner.BottomStart -> Anchor(x, 1f - y)
    Corner.BottomEnd -> Anchor(1f - x, 1f - y)
  }

internal const val HALF = 0.5f
