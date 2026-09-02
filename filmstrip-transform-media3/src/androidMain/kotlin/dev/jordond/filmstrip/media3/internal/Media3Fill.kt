package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.hlgSceneFromNits

/**
 * The red, green and blue an effect writes to paint [color] over a frame.
 *
 * An SDR frame reaches an effect still encoded, so the channels go in as authored. An HDR frame
 * reaches one as linear light, and a PQ frame and an HLG frame are normalised differently even
 * there, so the same colour is three different triples depending on where it is going.
 *
 * @param transfer The transfer function reaching the encoder, or null when the frame is SDR.
 */
internal fun fillComponents(
  color: Int,
  transfer: HdrTransfer?,
): FloatArray {
  if (transfer == null) {
    return floatArrayOf(
      ((color shr 16) and 0xFF) / 255f,
      ((color shr 8) and 0xFF) / 255f,
      (color and 0xFF) / 255f,
    )
  }

  val nits = hdrFillNits(color)

  return when (transfer) {
    // Display light, which is what media3 leaves in the texture once it has scaled PQ down.
    HdrTransfer.Pq -> FloatArray(3) { nits[it] / MEDIA3_HDR_PEAK_NITS }
    // Scene light, since media3 runs HLG's transfer function and stops before its display curve.
    HdrTransfer.Hlg -> hlgSceneFromNits(nits)
  }
}
