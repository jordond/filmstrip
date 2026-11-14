package dev.jordond.filmstrip.effects.overlay

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Composite an image over the video.
 *
 * Every measurement is a fraction of the frame the overlay is drawn on rather than a pixel count, so
 * an overlay lands in the same place at any preview or export resolution. That frame is the clip's
 * frame for an overlay added to a clip, and the output frame for one added to the composition.
 *
 * The picture is drawn in the color its file describes, through an embedded ICC profile, CICP or
 * PNG's own color chunks, and an untagged picture is read as sRGB. SDR output clips a tagged
 * picture's colors to BT.709, and an HDR grade keeps them inside BT.2020. A platform decoder that
 * cannot land the picture that way resolves the overlay as degraded or unsupported, naming why.
 *
 * @property image The image to composite.
 * @property corner Which corner the overlay sits in.
 * @property margin Inset from the corner, as a fraction of the shorter side of the frame it is drawn
 * on.
 * @property scale Width as a fraction of the width of the frame it is drawn on. The height follows the
 * image's own aspect.
 * @property opacity Alpha applied to the overlay, in `0f..1f`.
 * @property visibleDuring When the overlay is visible, or null for the whole composition.
 * @property animation How the overlay is drawn at each frame of its run, or null to hold it still.
 */
@Poko
public class ImageOverlay(
  public val image: ImageSource,
  public val corner: Corner,
  public val margin: Float = DEFAULT_MARGIN,
  public val scale: Float = DEFAULT_SCALE,
  public val opacity: Float = 1f,
  override val visibleDuring: TimeRange? = null,
  override val animation: OverlayAnimation? = null,
) : OverlayEffect {
  override val id: EffectId get() = EffectIds.IMAGE_OVERLAY

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
 * Composite [image] into [corner], sized and inset as fractions of the frame it is drawn on.
 *
 * That frame is the clip's frame when this is called on a clip, and the output frame when it is called
 * on the composition.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  corner: Corner,
  margin: Float = ImageOverlay.DEFAULT_MARGIN,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
  animation: OverlayAnimation? = null,
): EffectsBuilder = add(ImageOverlay(image, corner, margin, scale, opacity, visibleDuring, animation))

/**
 * Composite [image] into [corner], visible only during [at].
 *
 * Sized and inset the same way as the overload that takes a [TimeRange].
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  corner: Corner,
  at: ClosedRange<Duration>,
  margin: Float = ImageOverlay.DEFAULT_MARGIN,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  animation: OverlayAnimation? = null,
): EffectsBuilder = add(ImageOverlay(image, corner, margin, scale, opacity, TimeRange(at), animation))

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
