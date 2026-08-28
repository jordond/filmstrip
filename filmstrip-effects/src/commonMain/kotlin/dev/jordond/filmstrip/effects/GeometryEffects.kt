package dev.jordond.filmstrip.effects

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rotate the frame, counter-clockwise.
 *
 * The rotation is baked into the pixels rather than written as container metadata. Source
 * orientation is corrected automatically, so this is deliberate rotation on top of that.
 *
 * @property degrees Degrees counter-clockwise: 0, 90, 180 or 270.
 */
@Serializable
@SerialName(EffectIds.ROTATE)
@Poko
public class Rotate(
  public val degrees: Int,
) : EffectSpec {
  override val id: String get() = EffectIds.ROTATE

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Mirror the frame across an axis.
 *
 * @property axis The axis to mirror across.
 */
@Serializable
@SerialName(EffectIds.FLIP)
@Poko
public class Flip(
  public val axis: FlipAxis,
) : EffectSpec {
  override val id: String get() = EffectIds.FLIP

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Reframe to an aspect ratio, keeping the region around [anchor].
 *
 * @property aspect The target aspect ratio.
 * @property fit How the frame is fitted to [aspect].
 * @property anchor Where the retained region sits when [fit] is [Fit.Crop]. Centred by default.
 */
@Serializable
@SerialName(EffectIds.CROP)
@Poko
public class Crop(
  public val aspect: AspectRatio,
  public val fit: Fit = Fit.Crop,
  public val anchor: Anchor = Anchor.Center,
) : EffectSpec {
  override val id: String get() = EffectIds.CROP

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Crop to an explicit rectangle.
 *
 * Rotation runs before crop, so [rect] is normalised to the rotated frame rather than to the source
 * frame.
 *
 * @property rect The region to keep, as fractions of the frame entering the crop.
 */
@Serializable
@SerialName(EffectIds.CROP_RECT)
@Poko
public class CropRect(
  public val rect: NormalizedRect,
) : EffectSpec {
  override val id: String get() = EffectIds.CROP_RECT

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Set the output height in pixels. The width follows from the composition's aspect.
 *
 * @property targetHeight Target height in pixels. Rounded up to the encoder's alignment, and the
 * rounding is reported.
 * @property fit How the frame is fitted to [targetHeight].
 */
@Serializable
@SerialName(EffectIds.SCALE)
@Poko
public class Scale(
  public val targetHeight: Int,
  public val fit: Fit = Fit.Contain,
) : EffectSpec {
  override val id: String get() = EffectIds.SCALE

  override val stage: EffectStage get() = EffectStage.Geometry
}
