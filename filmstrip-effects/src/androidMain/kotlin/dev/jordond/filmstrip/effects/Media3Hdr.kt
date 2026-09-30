package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgSceneFromNits

/**
 * The peak media3 normalizes an HDR frame against, in cd/m2.
 *
 * media3's own transfer shader divides a PQ frame by this after decoding it against PQ's ten
 * thousand, and leaves an HLG frame alone, so one in an effect's texture is this many nits either
 * way. This is media3's figure rather than the format's.
 */
@InternalFilmstripApi
public const val MEDIA3_HDR_PEAK_NITS: Float = 1_000f

/**
 * Converts linear BT.2020 display light to the figures media3 holds an HDR frame in.
 *
 * A PQ frame is display light divided by [MEDIA3_HDR_PEAK_NITS]. An HLG frame is scene light, since
 * media3 runs HLG's transfer function and stops before its display curve. Whatever an effect paints
 * onto a kept grade goes through this, so a fill and an overlay texel of one colour land on one
 * figure.
 *
 * @param rgbNits Linear BT.2020 red, green and blue in cd/m2, as [authoredHdrNits] returns them.
 * @return Red, green and blue in media3's working space.
 */
@InternalFilmstripApi
public fun media3WorkingLight(
  rgbNits: FloatArray,
  transfer: HdrTransfer,
): FloatArray =
  when (transfer) {
    HdrTransfer.Pq -> FloatArray(3) { rgbNits[it] / MEDIA3_HDR_PEAK_NITS }
    HdrTransfer.Hlg -> hlgSceneFromNits(rgbNits)
  }
