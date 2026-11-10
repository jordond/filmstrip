package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.hdrColorMatrixUniforms
import dev.jordond.filmstrip.media.HdrTransfer

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
 * onto a kept grade goes through this, so a fill and an overlay texel of one color land on one
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

/**
 * The figures a pass reads a frame media3 holds in this transfer through, to move it to the SDR signal and back.
 *
 * media3 stores PQ against [MEDIA3_HDR_PEAK_NITS] and HLG against its nominal peak, and it hands HLG over as scene
 * light.
 */
@InternalFilmstripApi
public val HdrTransfer.media3SignalUniforms: HdrColorMatrixUniforms
  get() =
    hdrColorMatrixUniforms(
      white =
        HDR_REFERENCE_WHITE_NITS /
          when (this) {
            HdrTransfer.Pq -> MEDIA3_HDR_PEAK_NITS
            HdrTransfer.Hlg -> HLG_NOMINAL_PEAK_NITS
          },
      holdsSceneLight = this == HdrTransfer.Hlg,
    )
