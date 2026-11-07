package dev.jordond.filmstrip.webcodecs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sine at [hz] and [amplitude], [frames] long.
 */
internal fun toneOf(
  hz: Double,
  frames: Int,
  sampleRate: Int,
  amplitude: Float = 0.5f,
): FloatArray = FloatArray(frames) { amplitude * sin(2.0 * PI * hz * it / sampleRate).toFloat() }

/**
 * The frequency carrying the most energy in the middle of this signal, read to [DOMINANT_STEP_HZ] between
 * [DOMINANT_FROM_HZ] and [DOMINANT_TO_HZ].
 *
 * The edges are left out so an opening or a closing cut cannot move the answer.
 */
internal fun FloatArray.dominantHz(sampleRate: Int): Double {
  val length = minOf(size / 2, DOMINANT_MAX_FRAMES)
  val from = (size - length) / 2
  var best = DOMINANT_FROM_HZ
  var bestPower = -1.0
  var hz = DOMINANT_FROM_HZ
  while (hz <= DOMINANT_TO_HZ) {
    val power = powerAt(hz, from, length, sampleRate)
    if (power > bestPower) {
      best = hz
      bestPower = power
    }
    hz += DOMINANT_STEP_HZ
  }
  return best
}

/**
 * The root mean square of this signal between [from] and [to], which is what holds when a stretch changes the peak.
 */
internal fun FloatArray.rms(
  from: Int = 0,
  to: Int = size,
): Double {
  var sum = 0.0
  for (index in from until to) sum += this[index].toDouble() * this[index]
  return sqrt(sum / (to - from))
}

// Goertzel, so a scan of one signal reads only the bins it asks for.
private fun FloatArray.powerAt(
  hz: Double,
  from: Int,
  length: Int,
  sampleRate: Int,
): Double {
  val coefficient = 2.0 * cos(2.0 * PI * hz / sampleRate)
  var last = 0.0
  var beforeLast = 0.0
  for (index in from until from + length) {
    val next = this[index] + coefficient * last - beforeLast
    beforeLast = last
    last = next
  }
  return last * last + beforeLast * beforeLast - coefficient * last * beforeLast
}

/**
 * The resolution of [dominantHz], which is the tolerance a pitch reading is held to.
 */
internal const val DOMINANT_STEP_HZ: Double = 5.0

private const val DOMINANT_FROM_HZ = 100.0
private const val DOMINANT_TO_HZ = 2_000.0
private const val DOMINANT_MAX_FRAMES = 16_384
