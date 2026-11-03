package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

// The one definition of each HDR transfer, in double. The Float functions in HdrSignal.kt call these and round the
// result once, and a table that needs more than a Float of precision reads them directly.

/**
 * Encodes [nits] of display light as the SDR signal of the Float [sdrSignalFromNits], in double.
 *
 * Reference white sits at one and a highlight keeps its headroom above it. A negative figure is read as black.
 */
@InternalFilmstripApi
public fun sdrSignalFromNits(nits: Double): Double =
  (nits.coerceAtLeast(0.0) / HDR_REFERENCE_WHITE_NITS).pow(1.0 / SDR_DISPLAY_GAMMA)

/**
 * Decodes an SDR [signal] back to display light in cd/m2, the inverse of [sdrSignalFromNits], in double.
 *
 * A negative signal is read as black.
 */
@InternalFilmstripApi
public fun nitsFromSdrSignal(signal: Double): Double =
  signal.coerceAtLeast(0.0).pow(SDR_DISPLAY_GAMMA) * HDR_REFERENCE_WHITE_NITS

/**
 * The display light one channel of HLG [scene] light lands on at [HLG_NOMINAL_PEAK_NITS], in cd/m2 and in double.
 *
 * Raises the channel by [HLG_SYSTEM_GAMMA] on its own, the per channel reading of the opto-optical transfer.
 */
@InternalFilmstripApi
public fun hlgDisplayNitsFromScene(scene: Double): Double =
  scene.coerceAtLeast(0.0).pow(HLG_SYSTEM_GAMMA) * HLG_NOMINAL_PEAK_NITS

/**
 * The HLG scene light one channel of display light in [nits] came from, the inverse of [hlgDisplayNitsFromScene],
 * in double.
 */
@InternalFilmstripApi
public fun hlgSceneFromDisplayNits(nits: Double): Double =
  (nits.coerceAtLeast(0.0) / HLG_NOMINAL_PEAK_NITS).pow(1.0 / HLG_SYSTEM_GAMMA)

/**
 * Encodes one channel of HLG [scene] light as the SDR signal of [sdrSignalFromNits], in double.
 *
 * Runs [hlgDisplayNitsFromScene] and then [sdrSignalFromNits] without rounding in between.
 */
@InternalFilmstripApi
public fun sdrSignalFromHlgScene(scene: Double): Double = sdrSignalFromNits(hlgDisplayNitsFromScene(scene))

/**
 * Decodes an SDR [signal] back to one channel of HLG scene light, the inverse of [sdrSignalFromHlgScene], in
 * double.
 */
internal fun hlgSceneFromSdrSignal(signal: Double): Double = hlgSceneFromDisplayNits(nitsFromSdrSignal(signal))

/**
 * Encodes [nits] of linear light as a PQ signal in the range zero to one, in double.
 *
 * The inverse EOTF of SMPTE ST 2084. Light past [PQ_PEAK_NITS] encodes as the full signal.
 */
@InternalFilmstripApi
public fun pqSignalFromNits(nits: Double): Double {
  val normalized = (nits / PQ_PEAK_NITS).coerceIn(0.0, 1.0).pow(PQ_M1)

  return ((PQ_C1 + PQ_C2 * normalized) / (1.0 + PQ_C3 * normalized)).pow(PQ_M2)
}

/**
 * Decodes a PQ [signal] in the range zero to one back to linear light in cd/m2, in double.
 *
 * The EOTF of SMPTE ST 2084. A signal outside the range is clamped onto it first.
 */
@InternalFilmstripApi
public fun nitsFromPqSignal(signal: Double): Double {
  val encoded = signal.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
  val numerator = (encoded - PQ_C1).coerceAtLeast(0.0)
  val denominator = PQ_C2 - PQ_C3 * encoded

  return (numerator / denominator).pow(1.0 / PQ_M1) * PQ_PEAK_NITS
}

/**
 * Encodes HLG [scene] light, normalised against [HLG_NOMINAL_PEAK_NITS], as a signal in the range zero to one, in
 * double.
 *
 * The OETF from ITU-R BT.2100, per channel. A square root up to a twelfth and a log above it.
 */
@InternalFilmstripApi
public fun hlgSignalFromScene(scene: Double): Double {
  val value = scene.coerceAtLeast(0.0)

  return if (value <= 1.0 / 12.0) {
    sqrt3(value)
  } else {
    HLG_A * ln(12.0 * value - HLG_B) + HLG_C
  }.coerceIn(0.0, 1.0)
}

/**
 * Decodes an HLG [signal] in the range zero to one back to scene light, the inverse of [hlgSignalFromScene], in
 * double.
 */
@InternalFilmstripApi
public fun sceneFromHlgSignal(signal: Double): Double {
  val value = signal.coerceIn(0.0, 1.0)

  return if (value <= 0.5) {
    value * value / 3.0
  } else {
    (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
  }
}

private fun sqrt3(value: Double): Double = exp(ln(3.0 * value) / 2.0)

/**
 * Decodes one channel of an HLG [signal] to display light in cd/m2, reading the channel on its own, in double.
 *
 * Runs [sceneFromHlgSignal] and then [hlgDisplayNitsFromScene] without rounding in between. This is how a picture on a
 * kept grade is decoded, where [hlgNitsFromSignal] reads all three channels at once.
 */
@InternalFilmstripApi
public fun hlgDisplayNitsFromSignal(signal: Double): Double = hlgDisplayNitsFromScene(sceneFromHlgSignal(signal))

/**
 * Encodes one channel of display light in [nits] as an HLG signal in the range zero to one, the inverse of
 * [hlgDisplayNitsFromSignal], in double.
 *
 * Runs [hlgSceneFromDisplayNits] and then [hlgSignalFromScene] without rounding in between.
 */
@InternalFilmstripApi
public fun hlgSignalFromDisplayNits(nits: Double): Double = hlgSignalFromScene(hlgSceneFromDisplayNits(nits))

/**
 * Converts display light in cd/m2 to the scene light an HLG signal carries, in double.
 *
 * Undoes the opto-optical transfer, which is driven by the color's luminance, by dividing every channel by the same
 * figure. Normalised against [HLG_NOMINAL_PEAK_NITS], so one is that peak.
 *
 * @param rgbNits Linear BT.2020 red, green and blue.
 */
@InternalFilmstripApi
public fun hlgSceneFromNits(rgbNits: DoubleArray): DoubleArray {
  val display = DoubleArray(3) { (rgbNits[it] / HLG_NOMINAL_PEAK_NITS).coerceAtLeast(0.0) }
  val luminance = BT2020_LUMA_R * display[0] + BT2020_LUMA_G * display[1] + BT2020_LUMA_B * display[2]
  if (luminance <= 0.0) return DoubleArray(3)

  val divisor = luminance.pow((HLG_SYSTEM_GAMMA - 1.0) / HLG_SYSTEM_GAMMA)

  return DoubleArray(3) { display[it] / divisor }
}

/**
 * Converts the scene light an HLG signal carries to display light in cd/m2, the inverse of [hlgSceneFromNits], in
 * double.
 *
 * Raises the scene's luminance by [HLG_SYSTEM_GAMMA] and scales every channel by the same figure, so a saturated
 * color keeps its hue.
 *
 * @param rgbScene Linear BT.2020 red, green and blue scene light, normalised so one is [HLG_NOMINAL_PEAK_NITS].
 */
@InternalFilmstripApi
public fun hlgNitsFromScene(rgbScene: DoubleArray): DoubleArray {
  val scene = DoubleArray(3) { rgbScene[it].coerceAtLeast(0.0) }
  val luminance = BT2020_LUMA_R * scene[0] + BT2020_LUMA_G * scene[1] + BT2020_LUMA_B * scene[2]
  if (luminance <= 0.0) return DoubleArray(3)

  val multiplier = luminance.pow(HLG_SYSTEM_GAMMA - 1.0) * HLG_NOMINAL_PEAK_NITS

  return DoubleArray(3) { scene[it] * multiplier }
}

/**
 * Encodes display light in cd/m2 as an HLG signal in the range zero to one, in double.
 *
 * Runs [hlgSceneFromNits] and then [hlgSignalFromScene] on each channel without rounding in between.
 *
 * @param rgbNits Linear BT.2020 red, green and blue.
 */
@InternalFilmstripApi
public fun hlgSignalFromNits(rgbNits: DoubleArray): DoubleArray {
  val scene = hlgSceneFromNits(rgbNits)

  return DoubleArray(3) { hlgSignalFromScene(scene[it]) }
}

/**
 * Decodes an HLG signal back to display light in cd/m2, the inverse of [hlgSignalFromNits], in double.
 *
 * Runs [sceneFromHlgSignal] on each channel and then [hlgNitsFromScene] without rounding in between.
 *
 * @param rgbSignal Red, green and blue HLG signal in the range zero to one.
 */
@InternalFilmstripApi
public fun hlgNitsFromSignal(rgbSignal: DoubleArray): DoubleArray =
  hlgNitsFromScene(DoubleArray(3) { sceneFromHlgSignal(rgbSignal[it]) })

/**
 * The SDR signal this transfer's peak encodes to under [sdrSignalFromNits], in double.
 *
 * The Float [sdrSignalCeiling] is this rounded once. A table that divides by the ceiling reads this one, so its entries
 * carry no Float rounding.
 */
@InternalFilmstripApi
public val HdrTransfer.sdrSignalCeilingDouble: Double get() = sdrSignalFromNits(peakNits.toDouble())
