package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.color.icc.IccCurve
import dev.jordond.filmstrip.color.icc.IccProfile
import dev.jordond.filmstrip.color.icc.MatrixTrcProfile
import dev.jordond.filmstrip.color.icc.PCS_WHITE
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * How this description's color is evaluated, or null when the picture lands exactly as an untagged one does.
 *
 * Null covers a description that is sRGB, an ICC profile that does not parse as an RGB or grey profile, and CICP code
 * points or chromaticities this evaluation does not support.
 */
internal fun PictureColor.evaluator(): ColorEvaluation? =
  when (this) {
    is Icc -> {
      parsed?.let { ColorEvaluation(it, transfer = null) }
    }
    is Cicp -> {
      if (isSrgb || !isSupported) {
        null
      } else {
        describedProfile(cicpChromaticities(primaries), cicpCurve(transfer))?.let { ColorEvaluation(it, hdrTransfer) }
      }
    }
    is PngGamma -> {
      if (isSrgb || gamma?.let { it <= 0 } == true) {
        null
      } else {
        describedProfile(chromaticities?.values ?: SRGB_CHROMATICITIES, gamma?.let { pngCurve(it) } ?: SRGB_CURVE)
          ?.let { ColorEvaluation(it, transfer = null) }
      }
    }
  }

/**
 * The [profile] a description's color runs through, and the HDR [transfer] its curves decode, or null when its light
 * tops out at reference white.
 *
 * A PQ profile's light is relative to [HDR_REFERENCE_WHITE_NITS] and reaches past one. An HLG profile's is scene light,
 * which still needs the opto-optical transfer to become display light.
 */
internal class ColorEvaluation(
  val profile: IccProfile,
  val transfer: HdrTransfer?,
)

/**
 * A matrix and curves profile with [curve] on every channel and colorants built from [chromaticities], adapted to the
 * D50 connection space with Bradford from their own white, or null when either is missing or they span no color
 * space.
 */
internal fun describedProfile(
  chromaticities: DoubleArray?,
  curve: IccCurve?,
): IccProfile? {
  if (chromaticities == null || curve == null) return null
  val toXyz = rgbToXyz(chromaticities) ?: return null
  val white = xyToXyz(chromaticities[6], chromaticities[7])
  val d50 = multiply(bradford(white, PCS_WHITE), toXyz)
  if (d50.any { !it.isFinite() }) return null
  return MatrixTrcProfile(DoubleArray(9) { d50[(it % 3) * 3 + it / 3] }, List(3) { curve })
}

// PNG's gAMA stores the encoding exponent, so the curve to light raises to its inverse.
private fun pngCurve(gamma: Int): IccCurve = IccCurve.Gamma(PNG_UNIT / gamma)

private val SRGB_CHROMATICITIES = REC709_CHROMATICITIES.values
