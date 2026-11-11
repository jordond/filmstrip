package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.NearestMatrix
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason

/**
 * A way Android's decoder lands an overlay picture short of what its file stores, each Android's limit rather than
 * filmstrip's.
 *
 * @property reason What the picture gives up.
 * @property message What the overlay reports, naming Android's decoder.
 */
internal sealed class DecoderLimit(
  val reason: DegradationReason,
  val message: String,
) {
  /**
   * The [reason] and [message] as one cause of a degraded resolution.
   */
  val cause: Degradation
    get() = Degradation(reason, message)

  /**
   * A sixteen-bit picture the decoder hands back at eight bits a sample rather than as half floats.
   */
  data object EightBitCut : DecoderLimit(
    DegradationReason.PrecisionReduced,
    "This Android decodes the overlay image into eight bits a sample, so a picture stored at sixteen bits loses " +
      "the steps between eight-bit codes.",
  )

  /**
   * A twelve-bit AVIF decoded on API 36, where Android's AVIF decoder hands it back only at eight bits a sample.
   */
  data object AvifTwelveBitCut : DecoderLimit(
    DegradationReason.PrecisionReduced,
    "Android's AVIF decoder on API 36 decodes a twelve-bit AVIF only into eight bits a sample, so this picture " +
      "loses the steps between eight-bit codes.",
  )

  /**
   * A full range AVIF decoded before API 33. Android's AVIF decoder on API 31 reads every AVIF at limited range,
   * whatever the file records. API 32 is given API 31's landing.
   */
  data object AvifBeforeApi33 : DecoderLimit(
    DegradationReason.ColorSpaceConverted,
    "Android's AVIF decoder on API 31 reads every AVIF at limited range, and API 32 is taken to as well, so on this " +
      "API level this full range picture's YCbCr samples are turned into RGB at a range its file does not record.",
  )

  /**
   * A full range HEIC decoded on API 28, where Android's HEIC decoder reads every HEIC at limited range, whatever the
   * file records.
   */
  data object HeicBeforeApi29 : DecoderLimit(
    DegradationReason.ColorSpaceConverted,
    "Android's HEIC decoder on API 28 reads every HEIC at limited range, so on this API level this full range " +
      "picture's YCbCr samples are turned into RGB at a range its file does not record.",
  )

  /**
   * A HEIC or AVIF whose matrix Android's decoder on its API level does not read, so the codestream it is handed
   * states [nearest]'s matrix and the picture lands through it. Its [message] ends on [NearestMatrix.figures].
   *
   * @property avif Whether the picture is an AVIF rather than a HEIC.
   * @property nearest The matrix the picture lands through and how far that moves its colors.
   */
  data class LandedThrough(
    val avif: Boolean,
    val nearest: NearestMatrix,
  ) : DecoderLimit(
      DegradationReason.ColorSpaceConverted,
      "${decoderName(avif)} on this API level does not read this picture's matrix, so its YCbCr samples are turned " +
        "into RGB through matrix ${nearest.matrix} instead, ${nearest.figures}.",
    )
}

/**
 * The decoder Android hands an AVIF or a HEIC to, as a cause names it.
 */
internal fun decoderName(avif: Boolean): String = if (avif) "Android's AVIF decoder" else "Android's HEIC decoder"
