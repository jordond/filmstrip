package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.style.TextStyle
import kotlin.time.Duration

/**
 * Rotate counter-clockwise by [degrees], baked into the pixels.
 */
public fun EffectsBuilder.rotate(degrees: Int): EffectsBuilder = add(Rotate(degrees))

/**
 * Mirror across [axis].
 */
public fun EffectsBuilder.flip(axis: FlipAxis): EffectsBuilder = add(Flip(axis))

/**
 * Reframe to [aspect], keeping the region around [anchor].
 */
public fun EffectsBuilder.crop(
  aspect: AspectRatio,
  fit: Fit = Fit.Crop,
  anchor: Anchor = Anchor.Center,
): EffectsBuilder = add(Crop(aspect, fit, anchor))

/**
 * Crop to an explicit [rect], expressed in the frame rotation produced.
 */
public fun EffectsBuilder.crop(rect: NormalizedRect): EffectsBuilder = add(CropRect(rect))

/**
 * Pan and zoom from [from] to [to] over the clip's span.
 *
 * Valid on a clip alone, since the region it shows depends on where the frame sits inside that
 * clip.
 */
public fun EffectsBuilder.kenBurns(
  from: NormalizedRect,
  to: NormalizedRect,
  easing: Easing = Easing.EaseInOut,
): EffectsBuilder = add(KenBurns(from, to, easing))

/**
 * Scale the output to [targetHeight] pixels tall.
 */
public fun EffectsBuilder.scale(
  targetHeight: Int,
  fit: Fit = Fit.Contain,
): EffectsBuilder = add(Scale(targetHeight, fit))

/**
 * Composite [image] into [corner], sized and inset as fractions of the output frame.
 */
public fun EffectsBuilder.watermark(
  image: ImageSource,
  corner: Corner,
  margin: Float = Watermark.DEFAULT_MARGIN,
  scale: Float = Watermark.DEFAULT_SCALE,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
): EffectsBuilder = add(Watermark(image, corner, margin, scale, opacity, visibleDuring))

/**
 * Burn [text] into the video at [anchor].
 */
public fun EffectsBuilder.text(
  text: String,
  style: TextStyle = TextStyle.Default,
  anchor: Anchor = Anchor.BottomCenter,
  visibleDuring: TimeRange? = null,
): EffectsBuilder = add(Text(text, style, anchor, visibleDuring))

/**
 * Burn [text] into the video, visible only during [at].
 */
public fun EffectsBuilder.text(
  text: String,
  at: ClosedRange<Duration>,
  style: TextStyle = TextStyle.Default,
  anchor: Anchor = Anchor.BottomCenter,
): EffectsBuilder = add(Text(text, style, anchor, TimeRange(at)))

/**
 * Multiply every colour channel by [factor], where `1f` leaves the frame unchanged.
 */
public fun EffectsBuilder.brightness(factor: Float): EffectsBuilder = add(Brightness(factor))
