package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.text.SpannableString
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import dev.jordond.filmstrip.edit.TimeRange
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
 * @property bitmap The rasterized overlay. Not recycled on [release], because a frame processor may
 *   rebuild its shader programs from the same effect and would then be drawing a dead bitmap.
 */
internal class RasterOverlay(
  private val bitmap: Bitmap,
  private val visible: StaticOverlaySettings,
  private val visibleDuring: TimeRange?,
) : Media3TextOverlay() {
  private val hidden: StaticOverlaySettings? = visibleDuring?.let { visible.hidden() }

  override fun getText(presentationTimeUs: Long): SpannableString = EMPTY

  override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    val range = visibleDuring ?: return visible
    return if (presentationTimeUs.microseconds in range) visible else hidden ?: visible
  }

  private companion object {
    val EMPTY = SpannableString("")
  }
}
