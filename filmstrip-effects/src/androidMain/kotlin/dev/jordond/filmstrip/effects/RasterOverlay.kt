package dev.jordond.filmstrip.effects

import android.graphics.Bitmap
import android.text.SpannableString
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration.Companion.microseconds

/**
 * One already-rasterised overlay, waiting to be blended into a frame.
 *
 * Everything filmstrip composites is drawn to a bitmap at the exact pixel size it will occupy, so
 * this covers both a watermark and a run of burned-in text and the two share an [OverlayEffect].
 *
 * It extends [TextOverlay], not [BitmapOverlay]. `OverlayShaderProgram` sorts overlays into HDR
 * families by type and checks [TextOverlay] first, because it extends [BitmapOverlay]. A plain
 * [BitmapOverlay] on an HDR frame is treated as UltraHDR: it needs API 34, it needs the bitmap to
 * carry a gainmap, and it spends two of the fifteen sampler slots. [TextOverlay] is the only branch
 * that takes a plain SDR bitmap onto an HDR frame, which is what every overlay filmstrip draws is.
 * That branch is an `instanceof` rather than a documented contract, so it is worth re-reading when
 * media3 moves.
 *
 * Rasterising here rather than handing media3 a string is what gives wrapping at an authored width,
 * an alignment and a background plate, none of which [TextOverlay] exposes.
 *
 * Nothing downcasts to [TextOverlay], and [getText] is consulted only by the [getBitmap] this
 * overrides, so the string it returns never reaches anything.
 *
 * @property bitmap The rasterised overlay. Not recycled on [release], because a frame processor may
 *   rebuild its shader programs from the same effect and would then be drawing a dead bitmap.
 */
internal class RasterOverlay(
  private val bitmap: Bitmap,
  private val visible: StaticOverlaySettings,
  private val visibleDuring: TimeRange?,
) : TextOverlay() {
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
