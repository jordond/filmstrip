package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.displayLightCeiling
import dev.jordond.filmstrip.media.sdrSignalCeiling
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceExtendedLinearITUR_2020
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector

/**
 * Recombines the colour channels through [matrix], in the encoded domain the matrix is written for.
 *
 * A `CIContext` works in linear light, so a bare colour matrix would mix light rather than signal
 * and land somewhere the three backends that mix what the encoder writes never do. The tone curve
 * either side moves the frame into the encoded domain for the matrix and back out again. The clamp
 * runs once here rather than around each effect, since a run of colour effects has already been
 * folded into this one matrix by the time it arrives.
 *
 * On a kept grade the frame is display referred linear light with reference white at one, for PQ
 * and HLG alike, so the encoded domain is a bare power of it and the matrix runs between a gamma
 * adjust each way. The clamp there reaches up to [sdrSignalCeiling] rather than to white, since
 * that is where the format runs out, and it sits before the power back so a channel the matrix
 * pushed below zero comes out black. The working space carries sRGB primaries whatever the frame's,
 * so a BT.2020 frame is matched into its own primaries for the matrix and back out, and a mix or a
 * clamp lands on the channels the other backends mix and clamp.
 *
 * A matrix that scales all three channels by the same factor is the exception on a grade: it
 * commutes with the gamma either way, so it multiplies the light directly and clamps at
 * [displayLightCeiling], which is the same ceiling in the domain it ran in. The clamp is the reason
 * it still moves into the frame's own primaries first: a scale reads the same in either basis, and a
 * ceiling does not, so clamping in the working space would cut a saturated channel the frame's own
 * primaries leave alone.
 *
 * Each row's offset rides in the fourth component of its vector instead of in the bias vector, so
 * it is multiplied by alpha. Core Image holds a frame premultiplied, and a bias vector would lift a
 * transparent pixel off black into a colour a later blend then reads as real.
 *
 * @param colorSpace The primaries the frame is written in, read only on a grade.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withColorMatrix(
  matrix: ColorMatrix,
  transfer: HdrTransfer?,
  colorSpace: ColorSpace,
): CIImage {
  if (matrix.isIdentity) return this
  if (transfer != null) {
    val scale = matrix.uniformScale
    if (scale != null) {
      val gain = brightnessDisplayGain(scale)

      return inPrimariesOf(colorSpace) {
        recombined(scaleMatrix(gain, gain, gain)).clampedTo(transfer.displayLightCeiling)
      }
    }

    return inPrimariesOf(colorSpace) {
      raisedTo(1.0 / SDR_DISPLAY_GAMMA)
        .recombined(matrix)
        .clampedTo(transfer.sdrSignalCeiling)
        .raisedTo(SDR_DISPLAY_GAMMA)
    }
  }

  return imageByApplyingFilter("CILinearToSRGBToneCurve")
    .recombined(matrix)
    .imageByApplyingFilter("CIColorClamp")
    .imageByApplyingFilter("CISRGBToneCurveToLinear")
}

// Both spaces are linear and extended, so the trip each way is one matrix and a channel outside the
// sRGB gamut survives it as a negative rather than being cut off. Anything but BT.2020 already
// shares the working space's primaries and runs in place.
@OptIn(ExperimentalForeignApi::class)
private inline fun CIImage.inPrimariesOf(
  colorSpace: ColorSpace,
  block: CIImage.() -> CIImage,
): CIImage {
  if (colorSpace != ColorSpace.Bt2020) return block()
  val space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearITUR_2020) ?: return block()

  try {
    val moved = imageByColorMatchingWorkingSpaceToColorSpace(space) ?: return block()

    return moved.block().imageByColorMatchingColorSpaceToWorkingSpace(space) ?: block()
  } finally {
    CGColorSpaceRelease(space)
  }
}

// CIGammaAdjust reads a negative channel as black rather than handing pow a sign, so nothing has to
// floor the frame before the first power.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.raisedTo(power: Double): CIImage =
  imageByApplyingFilter(
    "CIGammaAdjust",
    mapOf("inputPower" to power),
  )

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.clampedTo(ceiling: Float): CIImage =
  imageByApplyingFilter(
    "CIColorClamp",
    mapOf(
      "inputMinComponents" to vector(0f, 0f, 0f, 0f),
      "inputMaxComponents" to vector(ceiling, ceiling, ceiling, 1f),
    ),
  )

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.recombined(matrix: ColorMatrix): CIImage =
  imageByApplyingFilter(
    "CIColorMatrix",
    mapOf(
      "inputRVector" to vector(matrix.rr, matrix.rg, matrix.rb, matrix.rBias),
      "inputGVector" to vector(matrix.gr, matrix.gg, matrix.gb, matrix.gBias),
      "inputBVector" to vector(matrix.br, matrix.bg, matrix.bb, matrix.bBias),
      "inputAVector" to vector(0f, 0f, 0f, 1f),
      "inputBiasVector" to vector(0f, 0f, 0f, 0f),
    ),
  )

@OptIn(ExperimentalForeignApi::class)
private fun vector(
  x: Float,
  y: Float,
  z: Float,
  w: Float,
): CIVector = CIVector.vectorWithX(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())
