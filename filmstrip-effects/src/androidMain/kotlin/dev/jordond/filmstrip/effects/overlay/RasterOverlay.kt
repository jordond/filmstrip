package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.text.SpannableString
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration.Companion.microseconds
import androidx.media3.common.util.Size as Media3Size
import androidx.media3.effect.OverlayEffect as Media3OverlayEffect
import androidx.media3.effect.TextOverlay as Media3TextOverlay

/**
 * One already-rasterized overlay, waiting to be blended into a frame.
 *
 * Everything filmstrip composites is drawn to a bitmap at the exact pixel size it will occupy, so
 * this covers both a watermark and a run of burned-in text and the two share an [Media3OverlayEffect].
 *
 * It extends [Media3TextOverlay], not [BitmapOverlay]. `OverlayShaderProgram` sorts overlays into HDR
 * families by type and checks [Media3TextOverlay] first, because it extends [BitmapOverlay]. A plain
 * [BitmapOverlay] on an HDR frame is treated as UltraHDR: it needs API 34, it needs the bitmap to
 * carry a gainmap, and it spends two of the fifteen sampler slots. [Media3TextOverlay] is the only branch
 * that takes a plain SDR bitmap onto an HDR frame, which is what every overlay filmstrip draws is.
 * That branch is an `instanceof` rather than a documented contract, so it is worth re-reading when
 * media3 moves.
 *
 * The text is rasterized here, which gives it wrapping at an authored width, an alignment and a
 * background plate, none of which [Media3TextOverlay] exposes.
 *
 * Nothing downcasts to [Media3TextOverlay], and [getText] is consulted only by the [getBitmap] this
 * overrides, so the string it returns never reaches anything.
 *
 * media3 hands the composition time to [getOverlaySettings], so [spec] is sampled there rather than
 * at resolve. The drawn range it is sampled against comes from the resolver, which derives it once, and an
 * overlay with nothing to animate answers the settings built once at resolve for the whole export.
 *
 * `OverlayShaderProgram.drawFrame` asks twice for one frame, once for the HDR luminance multiplier
 * and once for the matrix and the alpha, so the answer is held against the presentation time it was
 * sampled at and the second ask costs a comparison.
 *
 * One instance draws in one graph, since it holds that graph's texture and its last sample. Every graph builds its own
 * through the effect's factory, and they all read the same [bitmap].
 *
 * @property bitmap The rasterized overlay. Not recycled on [release], because every other graph drawing this overlay
 *   reads the same bitmap, and a later one would be drawing a dead one.
 * @property spec The overlay being drawn, sampled once per frame for its opacity, offset and scale.
 * @property placement Where the overlay lands with nothing animated, which an animation moves and
 *   resizes from.
 * @property raster [bitmap]'s own pixel size, which media3's scale is a multiplier on.
 * @property drawnRange The composition time range the overlay is drawn over, from `drawnRangeWithin`.
 * @property hdrLuminance The multiplier media3 reads [bitmap] through on an HDR frame, the one
 *   [rasterFor] mapped its texels against.
 * @property cells The cells of a sprite sheet or an animated picture, or null for an overlay that draws [bitmap] whole.
 *   They are drawn from one texture per cell, each made when its cell is first drawn and deleted on [release], so
 *   media3 clamps against the cell and never reads a neighbouring one. [bitmap] is then only the first cell, which
 *   nothing uploads.
 */
internal class RasterOverlay(
  private val bitmap: Bitmap,
  private val spec: OverlayEffect,
  private val placement: OverlayPlacement,
  private val raster: Size,
  private val drawnRange: TimeRange,
  private val hdrLuminance: Float = OverlaySettings.DEFAULT_HDR_LUMINANCE_MULTIPLIER,
  cells: CellBitmaps? = null,
) : Media3TextOverlay() {
  private val textures: CellTextures? = cells?.let(::CellTextures)
  private val visible: StaticOverlaySettings = placement.toOverlaySettings(raster, 1f, hdrLuminance)
  private val hidden: StaticOverlaySettings = visible.hidden()
  private val animated = AnimatedOverlaySettings(hdrLuminance)

  private var sampledAt: Long = UNSAMPLED
  private var sampled: OverlaySettings = visible

  override fun getText(presentationTimeUs: Long): SpannableString = EMPTY

  override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

  override fun getTextureId(presentationTimeUs: Long): Int =
    textures?.textureAt(presentationTimeUs) ?: super.getTextureId(presentationTimeUs)

  override fun getTextureSize(presentationTimeUs: Long): Media3Size =
    textures?.size ?: super.getTextureSize(presentationTimeUs)

  override fun release() {
    textures?.release()
    super.release()
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    if (presentationTimeUs != sampledAt) {
      sampled = settingsAt(presentationTimeUs)
      sampledAt = presentationTimeUs
    }
    return sampled
  }

  private fun settingsAt(presentationTimeUs: Long): OverlaySettings {
    val frame = spec.frameWithin(drawnRange, presentationTimeUs.microseconds) ?: return hidden
    val luminance = textures?.luminanceAt(presentationTimeUs)
    if (frame == OverlayFrame.Identity && luminance == null) return visible
    if (luminance != null) animated.hdrLuminance = luminance
    val moved = if (frame == OverlayFrame.Identity) placement else placement.animatedBy(frame)
    return animated.update(moved, raster, frame.opacity)
  }

  private companion object {
    val EMPTY = SpannableString("")

    // No frame carries this presentation time, so the first ask always samples.
    const val UNSAMPLED = Long.MIN_VALUE
  }
}
