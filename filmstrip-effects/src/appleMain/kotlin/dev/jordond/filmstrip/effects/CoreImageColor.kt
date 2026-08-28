package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.brightnessDisplayGain
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector

/**
 * Multiplies every colour channel by [scale], in the domain [transfer] says the frame is held in.
 *
 * A `CIContext` works in linear light, so on SDR a bare colour matrix would multiply light rather
 * than signal and land darker than the three backends that multiply what the encoder writes. The
 * tone curve either side of the matrix moves the frame into the encoded domain for the multiply and
 * back out again. Core Image carries values outside `0..1` through the rest of the chain, so a
 * factor that brightens is clamped there rather than at the encoder, which is where every other
 * backend clamps it.
 *
 * An HDR frame is already display-referred linear light, for PQ and HLG alike, so the display gain
 * multiplies it directly. Nothing clamps that path: HDR has headroom above white and a clamp would
 * invent a ceiling the format does not have.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withBrightness(
  scale: Float,
  transfer: HdrTransfer?,
): CIImage {
  if (scale == 1f) return this
  if (transfer != null) return multiplied(brightnessDisplayGain(scale).toDouble())

  val multiplied = imageByApplyingFilter("CILinearToSRGBToneCurve").multiplied(scale.toDouble())
  val clamped = if (scale > 1f) multiplied.imageByApplyingFilter("CIColorClamp") else multiplied

  return clamped.imageByApplyingFilter("CISRGBToneCurveToLinear")
}

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.multiplied(factor: Double): CIImage =
  imageByApplyingFilter(
    "CIColorMatrix",
    mapOf(
      "inputRVector" to CIVector.vectorWithX(factor, 0.0, 0.0, 0.0),
      "inputGVector" to CIVector.vectorWithX(0.0, factor, 0.0, 0.0),
      "inputBVector" to CIVector.vectorWithX(0.0, 0.0, factor, 0.0),
    ),
  )
