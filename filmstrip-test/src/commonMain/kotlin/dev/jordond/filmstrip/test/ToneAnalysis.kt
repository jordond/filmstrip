package dev.jordond.filmstrip.test

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures how much of one frequency a block of decoded audio carries.
 *
 * A mix test needs to say which source a level belongs to, and two sources at different frequencies
 * are told apart here rather than by peak amplitude, which only reports the sum. The Goertzel
 * recurrence is used instead of a transform because one frequency is wanted and not a spectrum, so
 * the whole measurement is a single pass with three accumulators.
 *
 * Every backend's export test shares this, so four measurements of one fade cannot disagree about
 * what they measured.
 */
public object ToneAnalysis {
  /**
   * How far a level measured out of a written file may sit from the gain the plan folded.
   *
   * One figure for all four backends, so a lowering that is quietly wrong on one of them cannot be
   * accommodated by loosening only that backend's own test. It is wide enough to absorb a lossy
   * codec and an encoder's own dither, and no wider: a fade read at half its range lands inside
   * this, and a fade that ran the wrong way or the wrong length does not.
   */
  public const val MEASURED_GAIN_TOLERANCE: Float = 0.05f

  /**
   * The amplitude of [frequencyHz] in [samples], on the same scale the samples are.
   *
   * Samples normalised to `-1f..1f` give an amplitude in `0f..1f`. The window should hold a whole
   * number of cycles or close to it, since a partial cycle leaks into neighbouring frequencies and
   * reads slightly low.
   *
   * @param samples Mono samples. Interleaved stereo must be downmixed or one channel taken first.
   * @param frequencyHz The frequency to measure.
   * @param sampleRate The rate [samples] were decoded at.
   */
  public fun amplitudeOf(
    samples: FloatArray,
    frequencyHz: Int,
    sampleRate: Int,
  ): Float {
    if (samples.isEmpty()) return 0f
    val k = 2.0 * cos(2.0 * PI * frequencyHz / sampleRate)
    var previous = 0.0
    var beforeThat = 0.0
    for (sample in samples) {
      val current = sample + k * previous - beforeThat
      beforeThat = previous
      previous = current
    }
    val power = previous * previous + beforeThat * beforeThat - k * previous * beforeThat
    // The recurrence accumulates half the amplitude across every sample, so the doubling and the
    // division put the answer back on the scale the samples came in on.
    return (2.0 * sqrt(max(power, 0.0)) / samples.size).toFloat()
  }

  /**
   * The amplitude of [frequencyHz] over the [length] samples starting at [from].
   *
   * This is how a test reads the middle of a fade rather than its ends, which is the only place a
   * ramp that is the wrong length or the wrong way round shows up.
   */
  public fun amplitudeOver(
    samples: FloatArray,
    from: Int,
    length: Int,
    frequencyHz: Int,
    sampleRate: Int,
  ): Float {
    val start = from.coerceIn(0, samples.size)
    val end = (from + length).coerceIn(start, samples.size)
    if (end == start) return 0f
    return amplitudeOf(samples.copyOfRange(start, end), frequencyHz, sampleRate)
  }

  /**
   * Interleaved [channels] samples reduced to mono by averaging.
   *
   * A backend hands back whatever layout its decoder produced, and the measurement wants one
   * channel's worth of signal.
   */
  public fun toMono(
    interleaved: FloatArray,
    channels: Int,
  ): FloatArray {
    if (channels <= 1) return interleaved
    val frames = interleaved.size / channels
    return FloatArray(frames) { frame ->
      var total = 0f
      for (channel in 0 until channels) total += interleaved[frame * channels + channel]
      total / channels
    }
  }
}
