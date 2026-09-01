package dev.jordond.filmstrip.effects

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a composited overlay lands, in real pixels of the frame it is drawn on.
 *
 * Both backends place an overlay by naming a point inside the overlay and a point inside the frame,
 * then bringing the two together. Resolving that pair here rather than in each lowering is what
 * keeps the same watermark in the same relative spot on either platform and at any resolution, the
 * same reason [Crop.retainedRect] is shared.
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

/**
 * The corner and margin that bring a watermark's own corner to this point.
 *
 * The inverse of what [Watermark.placedOn] does with [Watermark.corner] and [Watermark.margin], for
 * a caller holding a position a drag produced. The corner is the quadrant of the frame the point
 * falls in, and the margin is read back off the two insets from it. One margin answers for both
 * axes, so a point off the diagonal the forward arithmetic reaches takes the margin midway between
 * the two the insets imply.
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

/**
 * Resolves this watermark against the frame it is composited onto.
 *
 * The drawn width is [Watermark.scale] of the frame's width and the height follows [image]'s own
 * aspect, so a watermark never stretches. The inset is measured off the frame's shorter side, so
 * the same margin reads as the same distance from the edge in portrait and in landscape.
 *
 * A margin large enough to carry the overlay past the middle of the frame is held at the middle
 * rather than allowed to cross to the far side.
 *
 * @param frame The frame the overlay is drawn on, in pixels.
 * @param image The overlay image's own pixel size.
 * @return Where to draw it.
 */
public fun Watermark.placedOn(
  frame: Size,
  image: Size,
): OverlayPlacement {
  val width = (scale * frame.width).roundToInt().coerceAtLeast(1)
  val height =
    if (image.width <= 0) width else (width.toFloat() * image.height / image.width).roundToInt().coerceAtLeast(1)
  val inset = margin * min(frame.width, frame.height)
  return OverlayPlacement(
    size = Size(width, height),
    overlayAnchor = corner.anchor(),
    frameAnchor = corner.inset(inset.fractionOf(frame.width), inset.fractionOf(frame.height)),
  )
}

/**
 * Resolves this text against the frame it is burned into.
 *
 * Text carries no margin of its own, so the same point named by [Text.anchor] is taken in both the
 * text block and the frame: anchoring to [Anchor.BottomCenter] puts the block's bottom edge on the
 * frame's. [text] is the rasterised block's real pixel size, which each platform measures with its
 * own font stack.
 *
 * @param text The rasterised text block's pixel size.
 * @return Where to draw it.
 */
public fun Text.placedOn(text: Size): OverlayPlacement =
  OverlayPlacement(size = text, overlayAnchor = anchor, frameAnchor = anchor)

/**
 * The size a rasterised text block is drawn at on the frame entering the effect.
 *
 * A backend lays text out against [Attributes.layoutSize] and gets back a raster in that frame's
 * pixels. This brings it down to [Attributes.inputSize], which is the same frame for an export and
 * a smaller one for a preview rendering below it. Only the raster is resampled, so the lines were
 * already broken where the export breaks them.
 *
 * @param raster The rasterised text block's own pixel size.
 * @return The size to draw the block at.
 */
internal fun Attributes.drawnTextSize(raster: Size): Size {
  val layout = layoutSize.height
  if (layout <= 0 || layout == inputSize.height) return raster
  val scale = inputSize.height.toFloat() / layout
  return Size(
    (raster.width * scale).roundToInt().coerceAtLeast(1),
    (raster.height * scale).roundToInt().coerceAtLeast(1),
  )
}

private fun Float.fractionOf(side: Int): Float = if (side <= 0) 0f else (this / side).coerceIn(0f, HALF)

private fun Corner.anchor(): Anchor =
  when (this) {
    Corner.TopStart -> Anchor.TopStart
    Corner.TopEnd -> Anchor.TopEnd
    Corner.BottomStart -> Anchor.BottomStart
    Corner.BottomEnd -> Anchor.BottomEnd
  }

private fun Corner.inset(
  x: Float,
  y: Float,
): Anchor =
  when (this) {
    Corner.TopStart -> Anchor(x, y)
    Corner.TopEnd -> Anchor(1f - x, y)
    Corner.BottomStart -> Anchor(x, 1f - y)
    Corner.BottomEnd -> Anchor(1f - x, 1f - y)
  }

private const val HALF = 0.5f
