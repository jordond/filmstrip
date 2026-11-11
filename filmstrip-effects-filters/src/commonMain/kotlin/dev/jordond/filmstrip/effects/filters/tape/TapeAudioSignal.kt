package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order filter section, with its coefficients divided through by the leading one.
 *
 * A sample `x` leaves as `y = b0 x + b1 x1 + b2 x2 - a1 y1 - a2 y2`, where `x1` and `y1` are the sample before.
 */
@InternalFilmstripApi
public class Biquad internal constructor(
  public val b0: Double,
  public val b1: Double,
  public val b2: Double,
  public val a1: Double,
  public val a2: Double,
) {
  internal companion object {
    fun lowPass(
      cornerHz: Double,
      q: Double,
      sampleRate: Int,
    ): Biquad {
      val turn = 2.0 * PI * cornerHz / sampleRate
      val alpha = sin(turn) / (2.0 * q)
      val a0 = 1.0 + alpha
      val edge = (1.0 - cos(turn)) / a0
      return Biquad(edge / 2.0, edge, edge / 2.0, -2.0 * cos(turn) / a0, (1.0 - alpha) / a0)
    }

    fun highPass(
      cornerHz: Double,
      q: Double,
      sampleRate: Int,
    ): Biquad {
      val turn = 2.0 * PI * cornerHz / sampleRate
      val alpha = sin(turn) / (2.0 * q)
      val a0 = 1.0 + alpha
      val edge = (1.0 + cos(turn)) / a0
      return Biquad(edge / 2.0, -edge, edge / 2.0, -2.0 * cos(turn) / a0, (1.0 - alpha) / a0)
    }
  }
}

/**
 * The hiss draw for [channel] at timeline [frame] under [seed], from `-1.0` up to but not including `1.0`.
 *
 * Every frame draws on its own, so the hiss at a frame is the same whatever was played before it.
 */
@InternalFilmstripApi
public fun tapeNoiseAt(
  seed: Int,
  channel: Int,
  frame: Long,
): Double {
  val high = (frame ushr FRAME_BITS).toInt() and HASH_MASK
  val low = frame.toInt() and HASH_MASK
  return FilterNoise.snorm(FilterNoise.hash24(seed, PASS_HISS + channel, high, low))
}

/**
 * How many frames back the tape reads at timeline [frame], which is the delay the wow and the flutter bend around
 * [TapeAudioParams.delayFrames].
 *
 * Each is a sine of the frame's time whose start the seed picks, so the bend at a frame is the same whatever was
 * played before it.
 */
@InternalFilmstripApi
public fun tapeDelayAt(
  params: TapeAudioParams,
  frame: Long,
): Double {
  val seconds = frame.toDouble() / params.sampleRate
  val wow = params.wowFrames * turnSine(seconds * params.wowRateHz + startOf(params.seed, PASS_WOW))
  val flutter = params.flutterFrames * turnSine(seconds * params.flutterRateHz + startOf(params.seed, PASS_FLUTTER))
  return params.delayFrames + wow + flutter
}

// How much louder or quieter white noise leaves sections than it went in, as a ratio of RMS. It is the root of the
// energy in their impulse response, summed until what is left stops counting.
internal fun noiseGainOf(sections: List<Biquad>): Double {
  val state = DoubleArray(sections.size * 2)
  var energy = 0.0
  var quiet = 0
  var input = 1.0
  var count = 0
  while (quiet < QUIET_RUN && count < IMPULSE_LIMIT) {
    var sample = input
    sections.forEachIndexed { index, section -> sample = section.step(sample, state, index * 2) }
    energy += sample * sample
    quiet = if (sample * sample < energy * QUIET_SHARE) quiet + 1 else 0
    input = 0.0
    count++
  }
  return sqrt(energy)
}

// One sample through a section, whose two values of memory sit in state from at.
internal fun Biquad.step(
  input: Double,
  state: DoubleArray,
  at: Int,
): Double {
  val output = b0 * input + state[at]
  state[at] = b1 * input - a1 * output + state[at + 1]
  state[at + 1] = b2 * input - a2 * output
  return output
}

// The sine of a count of whole turns, which keeps its precision however large the count is.
private fun turnSine(turns: Double): Double = sin(2.0 * PI * (turns - floor(turns)))

// Where in its turn a wobble starts, from 0 up to 1.
private fun startOf(
  seed: Int,
  pass: Int,
): Double = FilterNoise.unorm(FilterNoise.hash24(seed, pass, 0, 0))

// Streams of the seed's draw, clear of the ones the picture takes.
private const val PASS_WOW = 64
private const val PASS_FLUTTER = 65
internal const val PASS_HISS = 66

// A frame is drawn from its low 24 bits and the 24 above them.
internal const val FRAME_BITS = 24

private const val QUIET_RUN = 256
private const val QUIET_SHARE = 1e-18
private const val IMPULSE_LIMIT = 1_000_000
