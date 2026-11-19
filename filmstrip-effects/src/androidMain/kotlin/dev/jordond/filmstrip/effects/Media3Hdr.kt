package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.color.hlgSignalFromScene
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.effects.color.HLG_OOTF_GLSL
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.glsl
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
 * Converts figures media3 holds an HDR frame in to the signal the grade's transfer encodes them as, the inverse of
 * [media3WorkingLight].
 *
 * A PQ frame is scaled back up by [MEDIA3_HDR_PEAK_NITS] and run through PQ's curve. An HLG frame is scene light, so
 * it only needs HLG's transfer function.
 *
 * @param working Red, green and blue in media3's working space.
 * @return Red, green and blue signal in [transfer], each from zero to one.
 */
@InternalFilmstripApi
public fun media3Signal(
  working: FloatArray,
  transfer: HdrTransfer,
): FloatArray =
  when (transfer) {
    HdrTransfer.Pq -> FloatArray(3) { pqSignalFromNits(working[it] * MEDIA3_HDR_PEAK_NITS) }
    HdrTransfer.Hlg -> FloatArray(3) { hlgSignalFromScene(working[it]) }
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

/**
 * GLSL for a pass that blends two colors media3 holds on a kept grade, defining `toBlendLight` and `fromBlendLight`.
 *
 * `toBlendLight` carries a color from media3's working space to display light, and `fromBlendLight` carries it back,
 * so a mix between the two runs in display light the way every backend blends a source with alpha. PQ is display light
 * already and both are identities, as they are with no [transfer]. HLG is scene light, which reaches display light
 * through [HLG_OOTF_GLSL], with media3's scene light read as a fraction of [HLG_NOMINAL_PEAK_NITS] both ways.
 *
 * @param transfer The grade the frame is held on, or null when the frame is SDR.
 */
@InternalFilmstripApi
public fun media3BlendLightGlsl(transfer: HdrTransfer?): String =
  if (transfer != HdrTransfer.Hlg) {
    """
    vec3 toBlendLight(vec3 color) { return color; }
    vec3 fromBlendLight(vec3 color) { return color; }
    """.trimIndent()
  } else {
    """
    $HLG_OOTF_GLSL
    vec3 toBlendLight(vec3 color) { return hlgDisplayFromScene(color) / ${HLG_NOMINAL_PEAK_NITS.glsl()}; }
    vec3 fromBlendLight(vec3 color) { return hlgSceneFromDisplay(color * ${HLG_NOMINAL_PEAK_NITS.glsl()}); }
    """.trimIndent()
  }
