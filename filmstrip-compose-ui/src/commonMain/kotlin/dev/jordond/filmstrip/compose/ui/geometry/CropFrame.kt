package dev.jordond.filmstrip.compose.ui.geometry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.compose.ui.interaction.CropHandle
import dev.jordond.filmstrip.geometry.NormalizedRect

/**
 * The mapping between a normalized crop rectangle and the view pixels the video is actually drawn into.
 *
 * [contentRect] is the letterboxed rectangle a video fills inside its surface, from `videoContentRect` . Every part of
 * the overlay reads its geometry through this rather than re-deriving the letterbox maths.
 *
 * @property contentRect Where the video is drawn, in the overlay's own coordinate space.
 */
@Poko
public class CropFrame(
  public val contentRect: Rect,
) {
  /**
   * [rect] mapped out of the normalized frame and into view pixels.
   */
  public fun toView(rect: NormalizedRect): Rect {
    val width = contentRect.width
    val height = contentRect.height
    return Rect(
      left = contentRect.left + rect.left * width,
      top = contentRect.top + rect.top * height,
      right = contentRect.left + rect.right * width,
      bottom = contentRect.top + rect.bottom * height,
    )
  }

  /**
   * [rect] mapped out of view pixels and back into the normalized frame.
   *
   * Returns [NormalizedRect.Full] when [contentRect] has no area, since there is no frame to divide by.
   */
  public fun toNormalized(rect: Rect): NormalizedRect {
    val width = contentRect.width
    val height = contentRect.height
    if (width <= 0f || height <= 0f) return NormalizedRect.Full

    return NormalizedRect(
      left = (rect.left - contentRect.left) / width,
      top = (rect.top - contentRect.top) / height,
      right = (rect.right - contentRect.left) / width,
      bottom = (rect.bottom - contentRect.top) / height,
    )
  }

  /**
   * The handle at [position] for a crop currently at [rect].
   *
   * A corner wins over an edge when both sit within [touchRadiusPx]. A position inside [rect] but away from every edge
   * answers [CropHandle.Body], and a position outside both the rectangle and its handles answers null.
   *
   * @param position Where the pointer is, in the overlay's own coordinate space.
   * @param rect The crop rectangle the handles are drawn around.
   * @param touchRadiusPx How far from a handle a position still counts as touching it.
   * @return The handle under [position], or null.
   */
  public fun handleAt(
    position: Offset,
    rect: NormalizedRect,
    touchRadiusPx: Float,
  ): CropHandle? {
    val view = toView(rect)

    val corners =
      listOf(
        CropHandle.TopLeft to Offset(view.left, view.top),
        CropHandle.TopRight to Offset(view.right, view.top),
        CropHandle.BottomRight to Offset(view.right, view.bottom),
        CropHandle.BottomLeft to Offset(view.left, view.bottom),
      )
    val nearestCorner = corners.minBy { (_, corner) -> (corner - position).getDistance() }
    if ((nearestCorner.second - position).getDistance() <= touchRadiusPx) return nearestCorner.first

    val edges =
      listOf(
        CropHandle.Top to distanceToSegment(position, Offset(view.left, view.top), Offset(view.right, view.top)),
        CropHandle.Right to distanceToSegment(position, Offset(view.right, view.top), Offset(view.right, view.bottom)),
        CropHandle.Bottom to
          distanceToSegment(position, Offset(view.left, view.bottom), Offset(view.right, view.bottom)),
        CropHandle.Left to distanceToSegment(position, Offset(view.left, view.top), Offset(view.left, view.bottom)),
      )
    val nearestEdge = edges.minBy { (_, distance) -> distance }
    if (nearestEdge.second <= touchRadiusPx) return nearestEdge.first

    return if (view.contains(position)) CropHandle.Body else null
  }
}

/**
 * The distance from [point] to the nearest point on the segment running from [start] to [end].
 */
private fun distanceToSegment(
  point: Offset,
  start: Offset,
  end: Offset,
): Float {
  val segment = end - start
  val lengthSquared = segment.x * segment.x + segment.y * segment.y
  if (lengthSquared == 0f) return (point - start).getDistance()

  val t = ((point.x - start.x) * segment.x + (point.y - start.y) * segment.y) / lengthSquared
  val clamped = t.coerceIn(0f, 1f)
  val projection = Offset(start.x + segment.x * clamped, start.y + segment.y * clamped)
  return (point - projection).getDistance()
}
