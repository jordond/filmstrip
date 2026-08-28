package dev.jordond.filmstrip.effects

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
@SerialName(EffectIds.WATERMARK)
@Poko
public class Watermark(
  public val image: ImageSource,
  public val corner: Corner,
  public val margin: Float = DEFAULT_MARGIN,
  public val scale: Float = DEFAULT_SCALE,
  public val opacity: Float = 1f,
  public val visibleDuring: TimeRange? = null,
) : EffectSpec {
  override val id: String get() = EffectIds.WATERMARK

  override val stage: EffectStage get() = EffectStage.Composite

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
 * Burn text into the video.
 *
 * The preview and the export lay the text out identically, so line breaks land on the same words in
 * both.
 *
 * @property text The text to draw.
 * @property style How the text is drawn.
 * @property anchor Where the text sits in the frame.
 * @property visibleDuring When the text is visible, or null for the whole composition.
 */
@Serializable
@SerialName(EffectIds.TEXT)
@Poko
public class Text(
  public val text: String,
  public val style: TextStyle = TextStyle.Default,
  public val anchor: Anchor = Anchor.BottomCenter,
  public val visibleDuring: TimeRange? = null,
) : EffectSpec {
  override val id: String get() = EffectIds.TEXT

  override val stage: EffectStage get() = EffectStage.Composite
}
