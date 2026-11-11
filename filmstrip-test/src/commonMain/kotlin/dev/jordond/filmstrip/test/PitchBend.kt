package dev.jordond.filmstrip.test

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How the pitch of a steady tone moves over a stretch of decoded audio.
 *
 * @property pitch The change in pitch at each reading, as a fraction of the pitch.
 * @property stepSeconds The time from one reading to the next.
 */
public class PitchBend(
  public val pitch: DoubleArray,
  public val stepSeconds: Double,
) {
  /**
   * The largest change in pitch at any reading.
   */
  public val steepest: Double get() = pitch.maxOfOrNull { abs(it) } ?: 0.0

  /**
   * How much of the bend repeats [rateHz] times a second, as a fraction of the pitch.
   *
   * The readings should span a whole number of repeats or close to it.
   */
  public fun depthAt(rateHz: Double): Double {
    if (pitch.isEmpty()) return 0.0
    var real = 0.0
    var imaginary = 0.0
    pitch.forEachIndexed { index, value ->
      real += value * cos(2 * PI * rateHz * index * stepSeconds)
      imaginary += value * sin(2 * PI * rateHz * index * stepSeconds)
    }
    return 2 * hypot(real, imaginary) / pitch.size
  }

  public companion object {
    /**
     * Reads the bend of the tone at [carrierHz] over the [length] samples of mono [samples] from [from].
     *
     * The phase of the tone is read over each [windowSeconds] in turn, and how far it moved from one window to the
     * next is how far the sound was running ahead or behind.
     *
     * @param windowSeconds How long each reading covers. It should hold a whole number of cycles of the tone.
     */
    public fun of(
      samples: FloatArray,
      from: Int,
      length: Int,
      carrierHz: Int,
      sampleRate: Int,
      windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
    ): PitchBend {
      val window = (windowSeconds * sampleRate).toInt()
      val readings = length / window
      val turn = 2 * PI * carrierHz / sampleRate
      val delays = DoubleArray(readings)
      var last = 0.0
      var wraps = 0.0
      for (reading in 0 until readings) {
        var inPhase = 0.0
        var quadrature = 0.0
        val first = from + reading * window
        for (frame in first until first + window) {
          inPhase += samples[frame] * cos(turn * frame)
          quadrature += samples[frame] * sin(turn * frame)
        }
        val phase = atan2(-inPhase, quadrature)
        if (reading > 0 && phase - last > PI) wraps -= 2 * PI
        if (reading > 0 && phase - last < -PI) wraps += 2 * PI
        last = phase
        delays[reading] = (phase + wraps) / turn
      }
      val pitch = DoubleArray((readings - 1).coerceAtLeast(0)) { (delays[it + 1] - delays[it]) / window }
      return PitchBend(pitch, window.toDouble() / sampleRate)
    }

    /**
     * How long a reading covers unless it is given a length, which holds ten cycles of a tone at 1 kHz.
     */
    public const val DEFAULT_WINDOW_SECONDS: Double = 0.01
  }
}
