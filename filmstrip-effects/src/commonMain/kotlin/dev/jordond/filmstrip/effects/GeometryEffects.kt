package dev.jordond.filmstrip.effects

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectScope
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.Easing
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
 * Pan and zoom across the frame over the clip's span.
 *
 * [from] and [to] are the regions filling the frame at the start of the clip and at its end, and
 * every frame between them shows the region [easing] paces to. Both are fractions of the frame
 * entering this effect, so a crop declared on the same clip has already chosen the framing this
 * travels within.
 *
 * The region is resampled to fill the frame it was measured against, so the frame keeps the size it
 * arrived at and a rect whose shape differs from the frame's stretches to fill it.
 *
 * Valid on a clip alone. Its result depends on where a frame sits in the clip's span, so it cannot
 * be fanned onto a run of clips the way a track effect is, and a plan refuses it anywhere else.
 *
 * @property from The region visible at the start of the clip.
 * @property to The region visible at the end.
 * @property easing How the motion is paced between them.
 */
@Serializable
@SerialName(EffectIds.KEN_BURNS)
@Poko
@ExperimentalFilmstripApi
public class KenBurns(
  public val from: NormalizedRect,
  public val to: NormalizedRect,
  public val easing: Easing = Easing.EaseInOut,
) : EffectSpec {
  override val id: String get() = EffectIds.KEN_BURNS

  override val stage: EffectStage get() = EffectStage.Geometry

  override val scope: EffectScope get() = EffectScope.ClipOnly
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
