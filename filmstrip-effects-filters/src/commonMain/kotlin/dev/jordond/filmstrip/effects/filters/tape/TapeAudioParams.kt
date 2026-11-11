package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * The figures one [TapeAudio] plays with at one sample rate.
 *
 * Built by [tapeAudioParams], and read by the kernel and by every lowering so they all play the same tape.
 *
 * @property sampleRate The sample rate the figures are for, in hertz.
 * @property seed Picks the hiss and where the wow and flutter start.
 * @property bandLimited Whether the band is narrowed at all. When false the two corners are not applied.
 * @property highPassHz The corner of the two-pole high-pass, in hertz.
 * @property lowPassHz The corner of the four-pole low-pass, in hertz.
 * @property hissRms The level of the hiss in the output, as an RMS on the -1 to 1 scale.
 * @property hissGain What a [tapeNoiseAt] draw is multiplied by ahead of the low-pass, so it leaves at [hissRms].
 * @property hissPerChannel Whether each channel draws its own hiss rather than all of them sharing the first's.
 * @property wowDepth The largest change in pitch the wow makes, as a fraction of the pitch.
 * @property wowRateHz How many times a second the wow repeats.
 * @property flutterDepth The largest change in pitch the flutter makes, as a fraction of the pitch.
 * @property flutterRateHz How many times a second the flutter repeats.
 * @property delayFrames The delay the pitch bends around, in frames, which is the latency the kernel reports.
 * @property monoMix How far each channel moves toward the mean of them all, from 0 to 1.
 */
@InternalFilmstripApi
public class TapeAudioParams internal constructor(
  public val sampleRate: Int,
  public val seed: Int,
  public val bandLimited: Boolean,
  public val highPassHz: Double,
  public val lowPassHz: Double,
  public val hissRms: Double,
  public val hissGain: Double,
  public val hissPerChannel: Boolean,
  public val wowDepth: Double,
  public val wowRateHz: Double,
  public val flutterDepth: Double,
  public val flutterRateHz: Double,
  public val delayFrames: Int,
  public val monoMix: Double,
) {
  /**
   * How far the wow moves the delay either side of [delayFrames], in frames.
   */
  public val wowFrames: Double
    get() = swingFrames(wowDepth, wowRateHz, sampleRate)

  /**
   * How far the flutter moves the delay either side of [delayFrames], in frames.
   */
  public val flutterFrames: Double
    get() = swingFrames(flutterDepth, flutterRateHz, sampleRate)

  /**
   * The sections of the low-pass, in the order they run.
   */
  public val lowPass: List<Biquad>
    get() = LOW_PASS_Q.map { Biquad.lowPass(lowPassHz, it, sampleRate) }

  /**
   * The one section of the high-pass.
   */
  public val highPass: Biquad
    get() = Biquad.highPass(highPassHz, HIGH_PASS_Q, sampleRate)
}

/**
 * The figures [spec] plays with on a stream at [sampleRate].
 *
 * A part's scaler and the intensity multiply, and the product scales the tape's figure. Hiss and pitch depth scale
 * linearly. A corner moves from the open band toward the tape's along a logarithmic scale, so half way is the
 * geometric mean of the two. The low-pass corner never passes [NYQUIST_SHARE] of the sample rate. Rates and the delay
 * the pitch bends around do not scale, so a change of strength alone keeps the latency.
 */
@InternalFilmstripApi
public fun tapeAudioParams(
  spec: TapeAudio,
  sampleRate: Int,
): TapeAudioParams {
  require(sampleRate > 0) { "A sample rate has to be positive, not $sampleRate." }
  val figures = figuresOf(spec.tape)
  val intensity = spec.intensity.decimal()
  val band = intensity * spec.bandLimit.decimal()
  val ceiling = NYQUIST_SHARE * sampleRate
  val lowPassHz = min(between(OPEN_LOW_PASS_HZ, figures.lowPassHz, band), ceiling)
  val wowRateHz = spec.wow.rateHz?.decimal() ?: WOW_RATE_HZ
  val flutterRateHz = spec.flutter.rateHz?.decimal() ?: FLUTTER_RATE_HZ
  val hissRms = 10.0.pow(-figures.hissDb / DB_PER_DECADE) * intensity * spec.hiss.decimal()
  val swing = swingFrames(figures.wow, wowRateHz, sampleRate) + swingFrames(figures.flutter, flutterRateHz, sampleRate)
  val bandLimited = band > 0.0
  val noiseGain = if (bandLimited) noiseGainOf(LOW_PASS_Q.map { Biquad.lowPass(lowPassHz, it, sampleRate) }) else 1.0
  return TapeAudioParams(
    sampleRate = sampleRate,
    seed = spec.seed,
    bandLimited = bandLimited,
    highPassHz = between(OPEN_HIGH_PASS_HZ, HIGH_PASS_HZ, band),
    lowPassHz = lowPassHz,
    hissRms = hissRms,
    hissGain = hissRms / (UNIFORM_RMS * noiseGain),
    hissPerChannel = !spec.mono,
    wowDepth = figures.wow * intensity * spec.wow.depth.decimal(),
    wowRateHz = wowRateHz,
    flutterDepth = figures.flutter * intensity * spec.flutter.depth.decimal(),
    flutterRateHz = flutterRateHz,
    delayFrames = ceil(swing).toInt() + DELAY_MARGIN_FRAMES,
    monoMix = if (spec.mono) intensity else 0.0,
  )
}

private class TapeFigures(
  val lowPassHz: Double,
  val hissDb: Double,
  val wow: Double,
  val flutter: Double,
)

private fun figuresOf(tape: TapeSpeed): TapeFigures =
  when (tape) {
    TapeSpeed.SP -> TapeFigures(lowPassHz = 10_000.0, hissDb = 43.0, wow = 0.0015, flutter = 0.0010)
    TapeSpeed.LP -> TapeFigures(lowPassHz = 7_000.0, hissDb = 41.0, wow = 0.0025, flutter = 0.0015)
    TapeSpeed.EP -> TapeFigures(lowPassHz = 5_000.0, hissDb = 39.0, wow = 0.0035, flutter = 0.0020)
  }

// A sine swinging the delay by this many frames bends the pitch by depth at its steepest.
private fun swingFrames(
  depth: Double,
  rateHz: Double,
  sampleRate: Int,
): Double = depth * sampleRate / (2.0 * PI * rateHz)

private fun between(
  open: Double,
  tape: Double,
  amount: Double,
): Double = open * (tape / open).pow(amount)

// A scaler as the decimal it was written as, so 0.2f reads as 0.2 on every platform.
private fun Float.decimal(): Double = toString().toDouble()

// Every speed rolls off under the same corner. The band a part scaled to nothing still has.
private const val HIGH_PASS_HZ = 100.0
private const val OPEN_HIGH_PASS_HZ = 5.0
private const val OPEN_LOW_PASS_HZ = 20_000.0

/**
 * The most of a sample rate the low-pass corner may sit at. A corner at the Nyquist frequency has no filter.
 */
internal const val NYQUIST_SHARE = 0.45

// The rates a wobble takes unless it is given one.
private const val WOW_RATE_HZ = 0.8
private const val FLUTTER_RATE_HZ = 9.0

// A two-pole Butterworth, and the two sections of a four-pole one.
internal const val HIGH_PASS_Q = 0.7071067811865476
internal val LOW_PASS_Q = listOf(0.5411961001461970, 1.3065629648763766)

// The RMS of a draw spread evenly over -1 to 1.
private const val UNIFORM_RMS = 0.5773502691896258
private const val DB_PER_DECADE = 20.0

// What the four-point read needs either side of the swing.
private const val DELAY_MARGIN_FRAMES = 2
