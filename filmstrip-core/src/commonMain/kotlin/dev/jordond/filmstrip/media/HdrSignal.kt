package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

// The transfer functions an HDR grade is written in, and the gains an effect authored against an
// SDR encoding turns into. Every backend reads its number from here rather than deriving its own.

/**
 * The luminance a diffuse white sits at on an HDR export, in cd/m2.
 *
 * Graphics authored for SDR carry no brightness of their own, so they need one chosen for them.
 * This is the level broadcast practice puts captions and titles at, low enough that white bars
 * beside a video do not glare against it.
 */
@InternalFilmstripApi
public const val HDR_REFERENCE_WHITE_NITS: Float = 203f

/**
 * The nominal peak an HLG signal is graded against, in cd/m2.
 *
 * HLG carries no absolute brightness, so its system gamma is a function of the display it is shown
 * on. This is the peak the gamma below was picked for.
 */
@InternalFilmstripApi
public const val HLG_NOMINAL_PEAK_NITS: Float = 1000f

/**
 * The peak an ST 2084 signal is defined against, in cd/m2.
 */
@InternalFilmstripApi
public const val PQ_PEAK_NITS: Float = 10_000f

/**
 * The gamma an SDR display applies to the signal it is fed.
 *
 * An effect defined as a multiply on an encoded SDR value moves light by this power, so it is what
 * turns such a factor into a figure an HDR pipeline can use.
 */
@InternalFilmstripApi
public const val SDR_DISPLAY_GAMMA: Double = 2.2

/**
 * The opto-optical gamma sitting between HLG's scene light and a display's, at
 * [HLG_NOMINAL_PEAK_NITS].
 */
@InternalFilmstripApi
public const val HLG_SYSTEM_GAMMA: Double = 1.2

/**
 * The power that takes HLG scene light to the SDR signal of [sdrSignalFromNits], per channel.
 *
 * Scene light reaches display light through [HLG_SYSTEM_GAMMA] and display light reaches the signal
 * through the inverse of [SDR_DISPLAY_GAMMA]. A backend that spells the pair as one exponent reads
 * it here rather than dividing the two again.
 */
@InternalFilmstripApi
public const val HLG_SCENE_TO_SDR_SIGNAL_GAMMA: Double = HLG_SYSTEM_GAMMA / SDR_DISPLAY_GAMMA

/**
 * The power back from that signal to HLG scene light, the inverse of [HLG_SCENE_TO_SDR_SIGNAL_GAMMA].
 */
@InternalFilmstripApi
public const val SDR_SIGNAL_TO_HLG_SCENE_GAMMA: Double = SDR_DISPLAY_GAMMA / HLG_SYSTEM_GAMMA

/**
 * What display light is multiplied by when an SDR brightness of [factor] is applied to an HDR
 * grade.
 *
 * `Brightness` is a multiply on an encoded SDR signal, and a display raises that signal by
 * [SDR_DISPLAY_GAMMA] on its way to light. Reading the factor as a bare linear multiply lands
 * roughly twice as bright.
 */
@InternalFilmstripApi
public fun brightnessDisplayGain(factor: Float): Float =
  factor
    .coerceAtLeast(0f)
    .toDouble()
    .pow(SDR_DISPLAY_GAMMA)
    .toFloat()

/**
 * What HLG scene light is multiplied by to move display light by [brightnessDisplayGain].
 *
 * A pipeline that runs HLG's inverse OETF and nothing else holds scene light, and scaling every
 * channel of that by `s` moves display light by `s` raised to [HLG_SYSTEM_GAMMA].
 */
@InternalFilmstripApi
public fun brightnessSceneGain(factor: Float): Float =
  brightnessDisplayGain(factor)
    .toDouble()
    .pow(1.0 / HLG_SYSTEM_GAMMA)
    .toFloat()

/**
 * What linear light is multiplied by to dim a background by [dim].
 *
 * `Fill.Blurred.backgroundGain` is defined against an encoded value, and linear light is not one.
 * Raising the gain by the same power an SDR display applies keeps a dimmed background looking the
 * same whether it was written to an SDR frame or an HDR one.
 */
@InternalFilmstripApi
public fun linearDimGain(dim: Float): Float = brightnessDisplayGain(1f - dim.coerceIn(0f, 1f))

/**
 * Encodes [nits] of display light as the signal an SDR display sitting at [HDR_REFERENCE_WHITE_NITS]
 * would have been fed to show it.
 *
 * The inverse of [SDR_DISPLAY_GAMMA] with white at reference white, left unclamped above one so a
 * highlight keeps its headroom. A colour effect authored against an encoded SDR signal is applied in
 * this domain on a kept grade, and [nitsFromSdrSignal] takes the result back to light.
 */
@InternalFilmstripApi
public fun sdrSignalFromNits(nits: Float): Float =
  (nits.coerceAtLeast(0f) / HDR_REFERENCE_WHITE_NITS)
    .toDouble()
    .pow(1.0 / SDR_DISPLAY_GAMMA)
    .toFloat()

/**
 * Decodes an SDR [signal], as [sdrSignalFromNits] produces it, back to display light in cd/m2.
 *
 * A negative signal is read as black, which is the floor a colour matrix's output gets.
 */
@InternalFilmstripApi
public fun nitsFromSdrSignal(signal: Float): Float =
  (signal.coerceAtLeast(0f).toDouble().pow(SDR_DISPLAY_GAMMA) * HDR_REFERENCE_WHITE_NITS).toFloat()

/**
 * The brightest display light this transfer carries, in cd/m2.
 */
@InternalFilmstripApi
public val HdrTransfer.peakNits: Float
  get() =
    when (this) {
      HdrTransfer.Pq -> PQ_PEAK_NITS
      HdrTransfer.Hlg -> HLG_NOMINAL_PEAK_NITS
    }

/**
 * The SDR signal this transfer's peak encodes to under [sdrSignalFromNits].
 *
 * A colour matrix applied on a kept grade is clamped here rather than at one, because that is where
 * the format runs out.
 */
@InternalFilmstripApi
public val HdrTransfer.sdrSignalCeiling: Float get() = sdrSignalFromNits(peakNits)

/**
 * Where [sdrSignalCeiling] sits for a backend holding display light with reference white at one.
 *
 * The same ceiling as [sdrSignalCeiling], in the domain a linear pipeline clamps in, so a backend
 * that multiplies light rather than the signal clamps in the same place as one that does not.
 */
@InternalFilmstripApi
public val HdrTransfer.displayLightCeiling: Float get() = peakNits / HDR_REFERENCE_WHITE_NITS

/**
 * The display light one channel of HLG [scene] light lands on at [HLG_NOMINAL_PEAK_NITS], in cd/m2.
 *
 * The opto-optical transfer raises each channel by [HLG_SYSTEM_GAMMA] on its own, which is the
 * reading [brightnessSceneGain] is built on.
 */
@InternalFilmstripApi
public fun hlgDisplayNitsFromScene(scene: Float): Float =
  (scene.coerceAtLeast(0f).toDouble().pow(HLG_SYSTEM_GAMMA) * HLG_NOMINAL_PEAK_NITS).toFloat()

/**
 * The HLG scene light one channel of display light in [nits] came from, the inverse of
 * [hlgDisplayNitsFromScene].
 */
@InternalFilmstripApi
public fun hlgSceneFromDisplayNits(nits: Float): Float =
  (nits.coerceAtLeast(0f) / HLG_NOMINAL_PEAK_NITS)
    .toDouble()
    .pow(1.0 / HLG_SYSTEM_GAMMA)
    .toFloat()

/**
 * Encodes one channel of HLG [scene] light as the SDR signal of [sdrSignalFromNits], through the
 * per channel opto-optical transfer.
 *
 * What a backend holding scene light computes before it applies a colour matrix.
 */
@InternalFilmstripApi
public fun sdrSignalFromHlgScene(scene: Float): Float = sdrSignalFromNits(hlgDisplayNitsFromScene(scene))

/**
 * Decodes an SDR [signal] back to one channel of HLG scene light, the inverse of
 * [sdrSignalFromHlgScene].
 */
@InternalFilmstripApi
public fun hlgSceneFromSdrSignal(signal: Float): Float = hlgSceneFromDisplayNits(nitsFromSdrSignal(signal))

/**
 * Encodes [nits] of linear light as a PQ signal, in the range zero to one.
 *
 * The inverse of the transfer function in SMPTE ST 2084, whose signal is absolute. A given code
 * value means the same brightness whatever it is shown on.
 */
@InternalFilmstripApi
public fun pqSignalFromNits(nits: Float): Float {
  val normalized = (nits / PQ_PEAK_NITS).coerceIn(0f, 1f).toDouble().pow(PQ_M1)

  return ((PQ_C1 + PQ_C2 * normalized) / (1.0 + PQ_C3 * normalized)).pow(PQ_M2).toFloat()
}

/**
 * Decodes a PQ [signal] in the range zero to one back to linear light, in cd/m2.
 */
@InternalFilmstripApi
public fun nitsFromPqSignal(signal: Float): Float {
  val encoded = signal.coerceIn(0f, 1f).toDouble().pow(1.0 / PQ_M2)
  val numerator = (encoded - PQ_C1).coerceAtLeast(0.0)
  val denominator = PQ_C2 - PQ_C3 * encoded

  return ((numerator / denominator).pow(1.0 / PQ_M1) * PQ_PEAK_NITS).toFloat()
}

/**
 * Encodes HLG [scene] light, normalised against [HLG_NOMINAL_PEAK_NITS], as a signal in the range
 * zero to one.
 *
 * The transfer function from ITU-R BT.2100, per channel. Whatever opto-optical transfer the scene
 * light needed has already run by the time a value reaches here.
 */
@InternalFilmstripApi
public fun hlgSignalFromScene(scene: Float): Float {
  val value = scene.coerceAtLeast(0f).toDouble()

  return if (value <= 1.0 / 12.0) {
    sqrt3(value)
  } else {
    HLG_A * ln(12.0 * value - HLG_B) + HLG_C
  }.toFloat().coerceIn(0f, 1f)
}

/**
 * Decodes an HLG [signal] in the range zero to one back to scene light.
 */
@InternalFilmstripApi
public fun sceneFromHlgSignal(signal: Float): Float {
  val value = signal.coerceIn(0f, 1f).toDouble()

  return if (value <= 0.5) {
    value * value / 3.0
  } else {
    (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
  }.toFloat()
}

private fun sqrt3(value: Double): Double = exp(ln(3.0 * value) / 2.0)

/**
 * The `m1` exponent of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_M1: Double = 2610.0 / 16384.0

/**
 * The `m2` exponent of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_M2: Double = (2523.0 / 4096.0) * 128.0

/**
 * The `c1` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C1: Double = 3424.0 / 4096.0

/**
 * The `c2` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C2: Double = (2413.0 / 4096.0) * 32.0

/**
 * The `c3` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C3: Double = (2392.0 / 4096.0) * 32.0

/**
 * The `a` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_A: Double = 0.17883277

/**
 * The `b` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_B: Double = 0.28466892

/**
 * The `c` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_C: Double = 0.55991073
