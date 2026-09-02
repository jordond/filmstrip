package dev.jordond.filmstrip.effects.overlay

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Composite an image over the video.
 *
 * Every measurement is a fraction of the output frame rather than a pixel count, so an overlay
 * lands in the same place at any preview or export resolution.
 *
 * @property image The image to composite.
 * @property corner Which corner the overlay sits in.
 * @property margin Inset from the corner, as a fraction of the output frame's shorter side.
 * @property scale Width as a fraction of the output frame's width. The height follows the image's
 * own aspect.
 * @property opacity Alpha applied to the overlay, in `0f..1f`.
 * @property visibleDuring When the overlay is visible, or null for the whole composition.
 */
@Serializable
@SerialName(EffectIds.IMAGE_OVERLAY)
@Poko
public class ImageOverlay(
  public val image: ImageSource,
  public val corner: Corner,
  public val margin: Float = DEFAULT_MARGIN,
  public val scale: Float = DEFAULT_SCALE,
  public val opacity: Float = 1f,
  override val visibleDuring: TimeRange? = null,
) : OverlayEffect {
  override val id: String get() = EffectIds.IMAGE_OVERLAY

  public companion object {
    /**
     * Four percent of the shorter side.
     */
    public const val DEFAULT_MARGIN: Float = 0.04f

    /**
     * A fifth of the frame width.
     */
    public const val DEFAULT_SCALE: Float = 0.2f
  }
}

/**
 * Composite [image] into [corner], sized and inset as fractions of the output frame.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  corner: Corner,
  margin: Float = ImageOverlay.DEFAULT_MARGIN,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
): EffectsBuilder = add(ImageOverlay(image, corner, margin, scale, opacity, visibleDuring))

/**
 * Resolves this overlay against the frame it is composited onto.
 *
 * The drawn width is [ImageOverlay.scale] of the frame's width and the height follows [image]'s
 * own aspect, so the image never stretches. The inset is measured off the frame's shorter side, so
 * the same margin reads as the same distance from the edge in portrait and in landscape.
 *
 * A margin large enough to carry the overlay past the middle of the frame is held at the middle
 * rather than allowed to cross to the far side.
 *
 * @param frame The frame the overlay is drawn on, in pixels.
 * @param image The overlay image's own pixel size.
 * @return Where to draw it.
 */
public fun ImageOverlay.placedOn(
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

private fun Float.fractionOf(side: Int): Float = if (side <= 0) 0f else (this / side).coerceIn(0f, HALF)
