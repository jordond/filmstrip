package dev.jordond.filmstrip.effects.overlay

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.geometry.Anchor
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
 * A GIF, an APNG or an animated WebP plays its own frames. Each frame is shown for the delay the file stores, and the
 * picture plays as many times as the file says, then holds its last frame. A GIF delay of 10 ms or less is shown as
 * 100 ms, the way a browser shows it. An APNG or a WebP plays every delay as stored and only a stored zero is shown as
 * 100 ms, so one with frames of 1 to 10 ms plays faster than in a browser tab. [visibleDuring] ends the picture sooner.
 * An animated AVIF is refused by name, and so is any animated file given a [sheet].
 *
 * @property image The image to composite.
 * @property position Where the overlay sits on the frame, tucked into a corner or centred on a point.
 * @property scale Width as a fraction of the width of the frame it is drawn on. The height follows the
 * image's own aspect.
 * @property opacity Alpha applied to the overlay, in `0f..1f`.
 * @property visibleDuring When the overlay is visible, or null for the whole composition.
 * @property animation How the overlay is drawn at each frame of its run, or null to hold it still.
 * @property sheet How [image] is cut into frames, or null to draw the whole picture. The picture is then a grid of
 * cells, one frame each, and the overlay draws one cell at a time. [scale] is the width of a cell, and the height
 * follows the cell's aspect.
 */
@Poko
public class ImageOverlay(
  public val image: ImageSource,
  public val position: OverlayPosition,
  public val scale: Float = DEFAULT_SCALE,
  public val opacity: Float = 1f,
  override val visibleDuring: TimeRange? = null,
  override val animation: OverlayAnimation? = null,
  public val sheet: SpriteSheet? = null,
) : OverlayEffect {
  /**
   * Builds an overlay tucked into [corner], inset by [margin] as a fraction of the shorter side of the frame it is
   * drawn on. Every other parameter is the same as the primary constructor's.
   */
  public constructor(
    image: ImageSource,
    corner: Corner,
    margin: Float = DEFAULT_MARGIN,
    scale: Float = DEFAULT_SCALE,
    opacity: Float = 1f,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
    sheet: SpriteSheet? = null,
  ) : this(image, OverlayPosition.Corner(corner, margin), scale, opacity, visibleDuring, animation, sheet)

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
 * on the composition. A [sheet] draws [image] one cell at a time.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  corner: Corner,
  margin: Float = ImageOverlay.DEFAULT_MARGIN,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
  animation: OverlayAnimation? = null,
  sheet: SpriteSheet? = null,
): EffectsBuilder = add(ImageOverlay(image, corner, margin, scale, opacity, visibleDuring, animation, sheet))

/**
 * Composite [image] into [corner], visible only during [at].
 *
 * Sized and inset the same way as the overload that takes a [TimeRange], and a [sheet] is drawn the same way.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  corner: Corner,
  at: ClosedRange<Duration>,
  margin: Float = ImageOverlay.DEFAULT_MARGIN,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  animation: OverlayAnimation? = null,
  sheet: SpriteSheet? = null,
): EffectsBuilder = add(ImageOverlay(image, corner, margin, scale, opacity, TimeRange(at), animation, sheet))

/**
 * Composite [image] with its centre on [at], sized as a fraction of the frame it is drawn on.
 *
 * That frame is the clip's frame when this is called on a clip, and the output frame when it is called on the
 * composition. A point near an edge leaves the overlay hanging off it, and on Android a point past an edge is held on
 * it. A [sheet] draws [image] one cell at a time.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  at: Anchor,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
  animation: OverlayAnimation? = null,
  sheet: SpriteSheet? = null,
): EffectsBuilder = add(ImageOverlay(image, OverlayPosition.At(at), scale, opacity, visibleDuring, animation, sheet))

/**
 * Composite [image] with its centre on [at], visible only during [during].
 *
 * Sized and placed the same way as the overload that takes a [TimeRange], and a [sheet] is drawn the same way.
 */
public fun EffectsBuilder.imageOverlay(
  image: ImageSource,
  at: Anchor,
  during: ClosedRange<Duration>,
  scale: Float = ImageOverlay.DEFAULT_SCALE,
  opacity: Float = 1f,
  animation: OverlayAnimation? = null,
  sheet: SpriteSheet? = null,
): EffectsBuilder =
  add(ImageOverlay(image, OverlayPosition.At(at), scale, opacity, TimeRange(during), animation, sheet))

/**
 * Resolves this overlay against the frame it is composited onto.
 *
 * The drawn width is [ImageOverlay.scale] of the frame's width and the height follows [image]'s
 * own aspect, so the image never stretches. For an overlay with a [ImageOverlay.sheet] that is the size of one cell.
 *
 * An [OverlayPosition.Corner] inset is measured off the frame's shorter side, so the same margin reads as the same
 * distance from the edge in portrait and in landscape. A margin large enough to carry the overlay past the middle of
 * the frame is held at the middle rather than allowed to cross to the far side. An [OverlayPosition.At] brings the
 * overlay's centre to its point as given.
 *
 * @param frame The frame the overlay is drawn on, in pixels.
 * @param image The pixel size of what is drawn, which is one cell for an overlay with a sheet.
 * @return Where to draw it.
 */
public fun ImageOverlay.placedOn(
  frame: Size,
  image: Size,
): OverlayPlacement {
  val width = (scale * frame.width).roundToInt().coerceAtLeast(1)
  val height =
    if (image.width <= 0) width else (width.toFloat() * image.height / image.width).roundToInt().coerceAtLeast(1)
  val size = Size(width, height)
  return when (val position = position) {
    is OverlayPosition.Corner -> {
      val inset = position.margin * min(frame.width, frame.height)
      OverlayPlacement(
        size = size,
        overlayAnchor = position.corner.anchor(),
        frameAnchor = position.corner.inset(inset.fractionOf(frame.width), inset.fractionOf(frame.height)),
      )
    }
    is OverlayPosition.At -> {
      OverlayPlacement(size, overlayAnchor = Anchor.Center, frameAnchor = position.center)
    }
  }
}

private fun Float.fractionOf(side: Int): Float = if (side <= 0) 0f else (this / side).coerceIn(0f, HALF)
