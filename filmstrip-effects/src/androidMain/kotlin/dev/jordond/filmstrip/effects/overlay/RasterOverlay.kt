package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.text.SpannableString
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration.Companion.microseconds
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
 * Rasterizing here rather than handing media3 a string is what gives wrapping at an authored width,
 * an alignment and a background plate, none of which [Media3TextOverlay] exposes.
 *
 * Nothing downcasts to [Media3TextOverlay], and [getText] is consulted only by the [getBitmap] this
 * overrides, so the string it returns never reaches anything.
 *
 * media3 hands the composition time to [getOverlaySettings], so [spec] is sampled there rather than
 * at resolve. The run it is sampled against comes from the resolver, which derives it once, and an
 * overlay with nothing to animate answers the settings built once at resolve and allocates nothing
 * for the rest of the export.
 *
 * `OverlayShaderProgram.drawFrame` asks twice for one frame, once for the HDR luminance multiplier
 * and once for the matrix and the alpha, so the answer is held against the presentation time it was
 * sampled at and the second ask costs a comparison.
 *
 * @property bitmap The rasterized overlay. Not recycled on [release], because a frame processor may
 *   rebuild its shader programs from the same effect and would then be drawing a dead bitmap.
 * @property spec The overlay being drawn, sampled once per frame for its opacity, offset and scale.
 * @property placement Where the overlay lands with nothing animated, which an animation moves and
 *   resizes from.
 * @property raster [bitmap]'s own pixel size, which media3's scale is a multiplier on.
 * @property run The composition time range the overlay is drawn over, from `runWithin`.
 */
@OptIn(InternalFilmstripApi::class)
internal class RasterOverlay(
  private val bitmap: Bitmap,
  private val spec: OverlayEffect,
  private val placement: OverlayPlacement,
  private val raster: Size,
  private val run: TimeRange,
) : Media3TextOverlay() {
  private val visible: StaticOverlaySettings = placement.toOverlaySettings(raster, 1f)
  private val hidden: StaticOverlaySettings = visible.hidden()
  private val animated = AnimatedOverlaySettings()

  private var sampledAt: Long = UNSAMPLED
  private var sampled: OverlaySettings = visible

  override fun getText(presentationTimeUs: Long): SpannableString = EMPTY

  override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    if (presentationTimeUs != sampledAt) {
      sampled = settingsAt(presentationTimeUs)
      sampledAt = presentationTimeUs
    }
    return sampled
  }

  private fun settingsAt(presentationTimeUs: Long): OverlaySettings {
    val frame = spec.frameWithin(run, presentationTimeUs.microseconds) ?: return hidden
    if (frame == OverlayFrame.Identity) return visible
    return animated.update(placement.animatedBy(frame), raster, frame.opacity)
  }

  private companion object {
    val EMPTY = SpannableString("")

    // No frame carries this presentation time, so the first ask always samples.
    const val UNSAMPLED = Long.MIN_VALUE
  }
}
