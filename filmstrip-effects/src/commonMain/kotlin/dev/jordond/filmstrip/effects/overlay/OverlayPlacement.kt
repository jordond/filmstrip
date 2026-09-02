package dev.jordond.filmstrip.effects.overlay

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

/**
 * Where a composited overlay lands, in real pixels of the frame it is drawn on.
 *
 * Both backends place an overlay by naming a point inside the overlay and a point inside the frame,
 * then bringing the two together. Resolving that pair here rather than in each lowering is what
 * keeps the same overlay in the same relative spot on either platform and at any resolution, the
 * same reason [retainedRect] is shared.
 *
 * @property size The overlay's drawn size, in pixels of the frame it lands on.
 * @property overlayAnchor The point inside the overlay that meets [frameAnchor], as a fraction of
 *   the overlay.
 * @property frameAnchor The point inside the frame the overlay is brought to, as a fraction of the
 *   frame.
 */
@Poko
@Immutable
public class OverlayPlacement(
  public val size: Size,
  public val overlayAnchor: Anchor,
  public val frameAnchor: Anchor,
)

/**
 * Where this placement puts the overlay on [frame], as a fraction of it.
 *
 * The point [OverlayPlacement.overlayAnchor] names inside the overlay lands on the point
 * [OverlayPlacement.frameAnchor] names inside the frame, and [OverlayPlacement.size] gives the
 * rectangle its extent. An overlay hanging over an edge answers a rectangle reaching outside
 * `0f..1f`, which is what a caller drawing the overlay's outline needs to see.
 *
 * @param frame The frame the overlay is drawn on, in pixels.
 * @return The rectangle the overlay covers, in the frame's normalised space.
 */
public fun OverlayPlacement.rectOn(frame: Size): NormalizedRect {
  if (frame.width <= 0 || frame.height <= 0) return NormalizedRect.Full

  val width = size.width.toFloat() / frame.width
  val height = size.height.toFloat() / frame.height
  val left = frameAnchor.x - overlayAnchor.x * width
  val top = frameAnchor.y - overlayAnchor.y * height

  return NormalizedRect(left = left, top = top, right = left + width, bottom = top + height)
}
