package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.pow

// The transfer functions an HDR grade is written in, and the gains an effect authored against an
// SDR encoding turns into. Every backend reads its number from here.

/**
 * What display light is multiplied by when an SDR brightness of [factor] is applied to an HDR
 * grade.
 *
 * `Brightness` is a multiply on an encoded SDR signal, and a display raises that signal by
 * [SDR_DISPLAY_GAMMA] on its way to light. Reading the factor as a bare linear multiply lands
 * roughly twice as bright.
 *
 * A gain past the largest 32-bit float is held at it, the way a folded run of brightness is, so a
 * backend is never handed an infinite one.
 */
@InternalFilmstripApi
public fun brightnessDisplayGain(factor: Float): Float =
  factor
    .coerceAtLeast(0f)
    .toDouble()
    .pow(SDR_DISPLAY_GAMMA)
    .coerceAtMost(Float.MAX_VALUE.toDouble())
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
 * highlight keeps its headroom. A color effect authored against an encoded SDR signal is applied in
 * this domain on a kept grade, and [nitsFromSdrSignal] takes the result back to light.
 */
@InternalFilmstripApi
public fun sdrSignalFromNits(nits: Float): Float = sdrSignalFromNits(nits.toDouble()).roundedToFloat()

/**
 * Decodes an SDR [signal], as [sdrSignalFromNits] produces it, back to display light in cd/m2.
 *
 * A negative signal is read as black, which is the floor a color matrix's output gets.
 */
@InternalFilmstripApi
public fun nitsFromSdrSignal(signal: Float): Float = nitsFromSdrSignal(signal.toDouble()).roundedToFloat()

/**
 * The SDR signal this transfer's peak encodes to under [sdrSignalFromNits].
 *
 * A color matrix applied on a kept grade is clamped here rather than at one, because that is where
 * the format runs out.
 */
@InternalFilmstripApi
public val HdrTransfer.sdrSignalCeiling: Float get() = sdrSignalCeilingDouble.roundedToFloat()

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
public fun hlgDisplayNitsFromScene(scene: Float): Float = hlgDisplayNitsFromScene(scene.toDouble()).roundedToFloat()

/**
 * The HLG scene light one channel of display light in [nits] came from, the inverse of
 * [hlgDisplayNitsFromScene].
 */
@InternalFilmstripApi
public fun hlgSceneFromDisplayNits(nits: Float): Float = hlgSceneFromDisplayNits(nits.toDouble()).roundedToFloat()

/**
 * Encodes one channel of HLG [scene] light as the SDR signal of [sdrSignalFromNits], through the
 * per channel opto-optical transfer.
 *
 * What a backend holding scene light computes before it applies a color matrix.
 */
@InternalFilmstripApi
public fun sdrSignalFromHlgScene(scene: Float): Float = sdrSignalFromHlgScene(scene.toDouble()).roundedToFloat()

/**
 * Decodes an SDR [signal] back to one channel of HLG scene light, the inverse of
 * [sdrSignalFromHlgScene].
 */
internal fun hlgSceneFromSdrSignal(signal: Float): Float = hlgSceneFromSdrSignal(signal.toDouble()).roundedToFloat()

/**
 * Encodes [nits] of linear light as a PQ signal, in the range zero to one.
 *
 * The inverse of the transfer function in SMPTE ST 2084, whose signal is absolute. A given code
 * value means the same brightness whatever it is shown on.
 */
@InternalFilmstripApi
public fun pqSignalFromNits(nits: Float): Float = pqSignalFromNits(nits.toDouble()).roundedToFloat()

/**
 * Decodes a PQ [signal] in the range zero to one back to linear light, in cd/m2.
 */
@InternalFilmstripApi
public fun nitsFromPqSignal(signal: Float): Float = nitsFromPqSignal(signal.toDouble()).roundedToFloat()

/**
 * Encodes HLG [scene] light, normalised against [HLG_NOMINAL_PEAK_NITS], as a signal in the range
 * zero to one.
 *
 * The transfer function from ITU-R BT.2100, per channel. Whatever opto-optical transfer the scene
 * light needed has already run by the time a value reaches here.
 */
@InternalFilmstripApi
public fun hlgSignalFromScene(scene: Float): Float = hlgSignalFromScene(scene.toDouble()).roundedToFloat()

/**
 * Decodes an HLG [signal] in the range zero to one back to scene light.
 */
@InternalFilmstripApi
public fun sceneFromHlgSignal(signal: Float): Float = sceneFromHlgSignal(signal.toDouble()).roundedToFloat()

/**
 * Decodes one channel of an HLG [signal] to display light in cd/m2, reading the channel on its own.
 *
 * The inverse transfer function and then [hlgDisplayNitsFromScene], rounded once at the end. A picture on a kept grade
 * is decoded this way, where [hlgNitsFromSignal] reads all three channels at once.
 */
@InternalFilmstripApi
public fun hlgDisplayNitsFromSignal(signal: Float): Float = hlgDisplayNitsFromSignal(signal.toDouble()).roundedToFloat()

/**
 * Encodes one channel of display light in [nits] as an HLG signal in the range zero to one, the inverse of
 * [hlgDisplayNitsFromSignal].
 *
 * [hlgSceneFromDisplayNits] and then the transfer function, rounded once at the end.
 */
@InternalFilmstripApi
public fun hlgSignalFromDisplayNits(nits: Float): Float = hlgSignalFromDisplayNits(nits.toDouble()).roundedToFloat()

/**
 * Converts display light in cd/m2 to the scene light an HLG signal carries.
 *
 * HLG stores what the camera saw, not what the display emits, and an opto-optical transfer sits
 * between the two. That transfer is driven by the color's luminance rather than by each channel on
 * its own, so a saturated color does not come back from a per-channel power.
 *
 * Normalised against [HLG_NOMINAL_PEAK_NITS], so one is that peak.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [authoredHdrNits] returns them.
 */
@InternalFilmstripApi
public fun hlgSceneFromNits(rgbNits: FloatArray): FloatArray = hlgSceneFromNits(rgbNits.widened()).rounded()

/**
 * Converts the scene light an HLG signal carries to display light in cd/m2, the inverse of
 * [hlgSceneFromNits].
 *
 * The opto-optical transfer raises the scene's luminance by [HLG_SYSTEM_GAMMA] and scales every
 * channel by the same figure to get there, so a saturated color keeps its hue. A grey lands where
 * [hlgDisplayNitsFromScene] puts each of its channels.
 *
 * @param rgbScene Linear BT.2020 red, green and blue scene light, normalised so one is
 * [HLG_NOMINAL_PEAK_NITS].
 */
@InternalFilmstripApi
public fun hlgNitsFromScene(rgbScene: FloatArray): FloatArray = hlgNitsFromScene(rgbScene.widened()).rounded()

/**
 * Encodes display light in cd/m2 as an HLG signal, in the range zero to one.
 *
 * Runs the inverse opto-optical transfer and then the transfer function from ITU-R BT.2100.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [authoredHdrNits] returns them.
 */
@InternalFilmstripApi
public fun hlgSignalFromNits(rgbNits: FloatArray): FloatArray = hlgSignalFromNits(rgbNits.widened()).rounded()

/**
 * Encodes linear light in cd/m2 for this transfer function, in the range zero to one.
 *
 * What a backend writing an encoded frame directly, rather than handing linear light to a pipeline
 * of its own, puts in the pixel.
 *
 * @param rgbNits Linear BT.2020 red, green and blue, as [authoredHdrNits] returns them.
 */
@InternalFilmstripApi
public fun HdrTransfer.signalFromNits(rgbNits: FloatArray): FloatArray =
  when (this) {
    HdrTransfer.Pq -> FloatArray(3) { pqSignalFromNits(rgbNits[it]) }
    HdrTransfer.Hlg -> hlgSignalFromNits(rgbNits)
  }

/**
 * Decodes an HLG signal back to display light in cd/m2, the inverse of [hlgSignalFromNits].
 *
 * Runs the inverse transfer function on each channel and then the opto-optical transfer, which is driven by the
 * color's luminance, so a saturated color comes back where [hlgSignalFromNits] took it from.
 *
 * @param rgbSignal Red, green and blue HLG signal in the range zero to one.
 * @return Linear BT.2020 red, green and blue in cd/m2.
 */
@InternalFilmstripApi
public fun hlgNitsFromSignal(rgbSignal: FloatArray): FloatArray = hlgNitsFromSignal(rgbSignal.widened()).rounded()

/**
 * Decodes a signal of this transfer function back to linear light in cd/m2, the inverse of [signalFromNits].
 *
 * PQ decodes each channel on its own. HLG goes through [hlgNitsFromSignal], whose opto-optical transfer reads all three
 * channels at once.
 *
 * @param rgbSignal Red, green and blue signal in the range zero to one, as [signalFromNits] returns them.
 * @return Linear BT.2020 red, green and blue in cd/m2.
 */
@InternalFilmstripApi
public fun HdrTransfer.nitsFromSignal(rgbSignal: FloatArray): FloatArray =
  when (this) {
    HdrTransfer.Pq -> FloatArray(3) { nitsFromPqSignal(rgbSignal[it]) }
    HdrTransfer.Hlg -> hlgNitsFromSignal(rgbSignal)
  }

// Kotlin/JS keeps a Float at double precision until it is stored in a FloatArray, so a figure goes through one to
// come out as the same 32 bits on every platform.
private fun Double.roundedToFloat(): Float = floatArrayOf(toFloat())[0]

private fun FloatArray.widened(): DoubleArray = DoubleArray(3) { this[it].toDouble() }

private fun DoubleArray.rounded(): FloatArray = FloatArray(3) { this[it].toFloat() }
