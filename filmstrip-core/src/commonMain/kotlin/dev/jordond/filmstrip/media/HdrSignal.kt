package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
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
public fun sdrSignalFromNits(nits: Float): Float = sdrSignalFromNits(nits.toDouble()).roundedToFloat()

/**
 * Decodes an SDR [signal], as [sdrSignalFromNits] produces it, back to display light in cd/m2.
 *
 * A negative signal is read as black, which is the floor a colour matrix's output gets.
 */
@InternalFilmstripApi
public fun nitsFromSdrSignal(signal: Float): Float = nitsFromSdrSignal(signal.toDouble()).roundedToFloat()

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
 * What a backend holding scene light computes before it applies a colour matrix.
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
 * between the two. That transfer is driven by the colour's luminance rather than by each channel on
 * its own, so a saturated colour does not come back from a per-channel power.
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
 * channel by the same figure to get there, so a saturated colour keeps its hue. A grey lands where
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
 * colour's luminance, so a saturated colour comes back where [hlgSignalFromNits] took it from.
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

/**
 * BT.2020's red luminance weight.
 *
 * The three weights sum to one and are what turn linear BT.2020 light into luminance, which is both
 * what HLG's opto-optical transfer is anchored on and what a Y'CbCr matrix reads.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_R: Float = 0.2627f

/**
 * BT.2020's green luminance weight.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_G: Float = 0.6780f

/**
 * BT.2020's blue luminance weight.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_B: Float = 0.0593f

/**
 * What Cb is scaled by on the way from blue minus luma to a BT.2020 NCL chroma channel.
 *
 * Follows from [BT2020_LUMA_B]: blue reaches its extreme at `1 - Kb` above luma, and the channel
 * spans plus or minus a half.
 */
@InternalFilmstripApi
public const val BT2020_CB_SCALE: Float = 2f * (1f - BT2020_LUMA_B)

/**
 * What Cr is scaled by, the red counterpart of [BT2020_CB_SCALE].
 */
@InternalFilmstripApi
public const val BT2020_CR_SCALE: Float = 2f * (1f - BT2020_LUMA_R)

/**
 * The BT.2020 non-constant luminance matrix from an R'G'B' signal to Y', Cb and Cr, one row per output.
 *
 * Each row holds the weights of red, green and blue in that order, and the rows come out as luma, Cb and Cr. Luma runs
 * from zero to one and each chroma channel spans plus or minus a half, before any scaling into codes. Built on
 * [BT2020_LUMA_R], its siblings and the chroma scales, so this and [BT2020_DECODE_ROWS] are the one place the matrix is
 * written.
 */
@InternalFilmstripApi
public val BT2020_ENCODE_ROWS: List<ChannelTriple> =
  run {
    val luma = doubleArrayOf(BT2020_LUMA_R.toDouble(), BT2020_LUMA_G.toDouble(), BT2020_LUMA_B.toDouble())
    listOf(
      ChannelTriple(luma),
      ChannelTriple(DoubleArray(3) { (primary(it, BLUE) - luma[it]) / BT2020_CB_SCALE }),
      ChannelTriple(DoubleArray(3) { (primary(it, RED) - luma[it]) / BT2020_CR_SCALE }),
    )
  }

/**
 * The inverse of [BT2020_ENCODE_ROWS], from Y', Cb and Cr back to an R'G'B' signal, one row per output.
 *
 * Each row holds the weights of luma, Cb and Cr in that order, and the rows come out as red, green and blue. Red and
 * blue are luma plus their chroma channel scaled back up, and green is what luma leaves once their shares of it are
 * taken out.
 */
@InternalFilmstripApi
public val BT2020_DECODE_ROWS: List<ChannelTriple> =
  run {
    val red = doubleArrayOf(1.0, 0.0, BT2020_CR_SCALE.toDouble())
    val blue = doubleArrayOf(1.0, BT2020_CB_SCALE.toDouble(), 0.0)
    val green =
      DoubleArray(3) { (primary(it, LUMA) - BT2020_LUMA_R * red[it] - BT2020_LUMA_B * blue[it]) / BT2020_LUMA_G }
    listOf(ChannelTriple(red), ChannelTriple(green), ChannelTriple(blue))
  }

/**
 * The R'G'B' signal that moves Cb and Cr by the gap between [TEN_BIT_CHROMA_MID] and [TEN_BIT_LUMA_FLOOR], with luma
 * left where it was.
 *
 * Holds red, green and blue in that order, each the [BT2020_DECODE_ROWS] weight of Cb and Cr times that gap over
 * [TEN_BIT_CHROMA_RANGE]. Adding it to a signal ahead of [BT2020_ENCODE_ROWS] writes Cb and Cr as their distance from
 * black's code rather than from the chroma middle, so all three channels share one offset.
 */
@InternalFilmstripApi
public val BT2020_CHROMA_SHIFT: ChannelTriple =
  run {
    val fromBlack = (TEN_BIT_CHROMA_MID - TEN_BIT_LUMA_FLOOR).toDouble() / TEN_BIT_CHROMA_RANGE
    ChannelTriple(DoubleArray(3) { (BT2020_DECODE_ROWS[it][1] + BT2020_DECODE_ROWS[it][2]) * fromBlack })
  }

/**
 * Three doubles, one per channel, which a caller reads by index but cannot change.
 *
 * Carries each row of [BT2020_ENCODE_ROWS] and [BT2020_DECODE_ROWS], and [BT2020_CHROMA_SHIFT], so the shared matrix
 * stays as core wrote it. The channel order is the one the holding declaration names.
 */
@InternalFilmstripApi
public class ChannelTriple internal constructor(
  private val values: DoubleArray,
) {
  /**
   * Reads the value for one channel.
   *
   * @param index The channel, from zero to two.
   */
  public operator fun get(index: Int): Double = values[index]
}

private const val RED = 0
private const val BLUE = 2
private const val LUMA = 0

private fun primary(
  channel: Int,
  of: Int,
): Double = if (channel == of) 1.0 else 0.0

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
