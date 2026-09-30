package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits

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

  return media3WorkingLight(authoredHdrNits(color), transfer)
}
