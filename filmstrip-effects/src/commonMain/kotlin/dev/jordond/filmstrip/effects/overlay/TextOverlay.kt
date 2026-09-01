package dev.jordond.filmstrip.effects.overlay

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.Duration

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
@SerialName(EffectIds.TEXT_OVERLAY)
@Poko
public class TextOverlay(
  public val text: String,
  public val style: TextStyle = TextStyle.Default,
  public val anchor: Anchor = Anchor.BottomCenter,
  override val visibleDuring: TimeRange? = null,
) : OverlayEffect {
  override val id: String get() = EffectIds.TEXT_OVERLAY
}

/**
 * Burn [text] into the video at [anchor].
 */
public fun EffectsBuilder.textOverlay(
  text: String,
  style: TextStyle = TextStyle.Default,
  anchor: Anchor = Anchor.BottomCenter,
  visibleDuring: TimeRange? = null,
): EffectsBuilder = add(TextOverlay(text, style, anchor, visibleDuring))

/**
 * Burn [text] into the video, visible only during [at].
 */
public fun EffectsBuilder.textOverlay(
  text: String,
  at: ClosedRange<Duration>,
  style: TextStyle = TextStyle.Default,
  anchor: Anchor = Anchor.BottomCenter,
): EffectsBuilder = add(TextOverlay(text, style, anchor, TimeRange(at)))

/**
 * Resolves this text against the frame it is burned into.
 *
 * Text carries no margin of its own, so the same point named by [TextOverlay.anchor] is taken in
 * both the text block and the frame: anchoring to [Anchor.BottomCenter] puts the block's bottom edge
 * on the frame's. [text] is the rasterised block's real pixel size, which each platform measures
 * with its own font stack.
 *
 * @param text The rasterised text block's pixel size.
 * @return Where to draw it.
 */
public fun TextOverlay.placedOn(text: Size): OverlayPlacement =
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
