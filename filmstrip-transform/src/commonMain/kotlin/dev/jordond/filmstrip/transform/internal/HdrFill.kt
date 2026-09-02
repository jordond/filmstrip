package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.pqSignalFromNits
import kotlin.math.pow

// A fill colour is authored as sRGB, and an HDR export is neither sRGB nor a fixed brightness. The
// conversion is derived once here so two backends cannot disagree about how bright white is. Every
// figure below is absolute, in cd/m2, and a backend divides by whatever its own pipeline normalises
// against.

/**
 * Converts a packed sRGB colour to linear BT.2020 light, in cd/m2.
 *
 * Alpha is ignored. A fully saturated channel lands at [HDR_REFERENCE_WHITE_NITS] rather than at
 * the format's peak, so white bars read as paper white next to the picture instead of as a
 * highlight.
 *
 * @return Red, green and blue in that order.
 */
@InternalFilmstripApi
public fun hdrFillNits(color: Int): FloatArray {
  val red = srgbToLinear(((color shr 16) and 0xFF) / 255f)
  val green = srgbToLinear(((color shr 8) and 0xFF) / 255f)
  val blue = srgbToLinear((color and 0xFF) / 255f)

  return FloatArray(3) { row ->
    val wide =
      BT709_TO_BT2020[row * 3] * red +
        BT709_TO_BT2020[row * 3 + 1] * green +
        BT709_TO_BT2020[row * 3 + 2] * blue

    (wide * HDR_REFERENCE_WHITE_NITS).toFloat()
  }
}

/**
 * Converts display light in cd/m2 to the scene light an HLG signal carries.
 *
 * HLG stores what the camera saw, not what the display emits, and an opto-optical transfer sits
 * between the two. That transfer is driven by the colour's luminance rather than by each channel on
 * its own, so a saturated colour does not come back from a per-channel power.
 *
 * Normalised against [HLG_NOMINAL_PEAK_NITS], so one is that peak.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [hdrFillNits] returns them.
 */
@InternalFilmstripApi
public fun hlgSceneFromNits(rgbNits: FloatArray): FloatArray {
  val display = FloatArray(3) { (rgbNits[it] / HLG_NOMINAL_PEAK_NITS).coerceAtLeast(0f) }
  val luminance = BT2020_LUMA_R * display[0] + BT2020_LUMA_G * display[1] + BT2020_LUMA_B * display[2]
  if (luminance <= 0f) return FloatArray(3)

  // The system gamma raised the scene's luminance to reach the display's, so undoing it divides
  // every channel by the same figure rather than reshaping the colour.
  val divisor = luminance.toDouble().pow((HLG_SYSTEM_GAMMA - 1.0) / HLG_SYSTEM_GAMMA)

  return FloatArray(3) { (display[it] / divisor).toFloat() }
}

/**
 * Encodes display light in cd/m2 as an HLG signal, in the range zero to one.
 *
 * Runs the inverse opto-optical transfer and then the transfer function from ITU-R BT.2100.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [hdrFillNits] returns them.
 */
@InternalFilmstripApi
public fun hlgSignalFromNits(rgbNits: FloatArray): FloatArray {
  val scene = hlgSceneFromNits(rgbNits)

  return FloatArray(3) { hlgSignalFromScene(scene[it]) }
}

/**
 * Encodes linear light in cd/m2 for this transfer function, in the range zero to one.
 *
 * What a backend writing an encoded frame directly, rather than handing linear light to a pipeline
 * of its own, puts in the pixel.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [hdrFillNits] returns them.
 */
@InternalFilmstripApi
public fun HdrTransfer.signalFromNits(rgbNits: FloatArray): FloatArray =
  when (this) {
    HdrTransfer.Pq -> FloatArray(3) { pqSignalFromNits(rgbNits[it]) }
    HdrTransfer.Hlg -> hlgSignalFromNits(rgbNits)
  }

/**
 * The sRGB transfer function, turning an encoded channel into linear light.
 */
private fun srgbToLinear(encoded: Float): Float {
  val value = encoded.coerceIn(0f, 1f).toDouble()

  return if (value <= SRGB_KNEE) {
    value / SRGB_SLOPE
  } else {
    ((value + SRGB_OFFSET) / (1.0 + SRGB_OFFSET)).pow(SRGB_GAMMA)
  }.toFloat()
}

// BT.709 and BT.2020 share a white point, so this is a change of primaries with no adaptation and
// every row sums to one.
private val BT709_TO_BT2020 =
  doubleArrayOf(
    0.6274039,
    0.3292830,
    0.0433131,
    0.0690973,
    0.9195404,
    0.0113623,
    0.0163914,
    0.0880133,
    0.8955953,
  )

private const val SRGB_KNEE = 0.04045
private const val SRGB_SLOPE = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_GAMMA = 2.4
