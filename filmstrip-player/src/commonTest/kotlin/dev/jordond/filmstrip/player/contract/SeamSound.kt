package dev.jordond.filmstrip.player.contract

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The first channel of audio a backend wrote or played, on the -1 to 1 scale.
 *
 * @property samples One value per frame.
 * @property sampleRate The rate [samples] run at.
 */
class HeardSound(
  val samples: FloatArray,
  val sampleRate: Int,
)

/**
 * What [measureSeam] read at the join of two clips of one tone.
 *
 * @property quietRun The longest run around the seam that sat near silence, which is a gap in the sound.
 * @property phaseStep How far the tone after the seam sits ahead of the tone before it, folded to half a period either
 *   way. Positive is sound skipped and negative is sound repeated.
 */
class SeamMeasure(
  val quietRun: Duration,
  val phaseStep: Duration,
)

/**
 * The most a seam may break the tone, as one backend's limit.
 *
 * @property quietRun The longest silence at the seam.
 * @property phaseStep The largest step in the tone's phase, as a time.
 */
class SeamBound(
  val quietRun: Duration,
  val phaseStep: Duration,
)

/**
 * The bound a seam is held to unless a backend names a limit of its own: no more silence than four frames of 48 kHz
 * audio, and the tone picked up again within one.
 */
val SHARED_SEAM_BOUND = SeamBound(quietRun = 1.seconds * 4 / 48_000, phaseStep = 1.seconds / 48_000)

/**
 * The pitch of the tone every fixture's audio carries.
 */
const val TONE_HZ = 440

/**
 * One AAC access unit at 48 kHz, the smallest piece of sound an AAC decoder gives back.
 */
val AAC_ACCESS_UNIT: Duration = 1.seconds * 1_024 / 48_000

/**
 * Measures the join at [seamAt] in [heard], which holds one [TONE_HZ] sine across it.
 *
 * The tone's phase is read over a window of whole periods either side of the seam, each against the same absolute
 * clock, so a continuous tone reads the same phase on both sides and sound skipped or repeated at the seam reads as a
 * step. A step is only known to within a period, so a loss longer than that is told apart by [SeamMeasure.quietRun] or
 * by the length of what played.
 */
fun measureSeam(
  heard: HeardSound,
  seamAt: Duration,
): SeamMeasure {
  val rate = heard.sampleRate
  val window = (PHASE_PERIODS * rate.toDouble() / TONE_HZ).roundToInt()
  val near = frameOf(PHASE_CLEARANCE, rate)
  val seam = frameOf(seamAt, rate)
  val before = phaseOver(heard.samples, seam - near - window, window, rate)
  val after = phaseOver(heard.samples, seam + near, window, rate)
  var step = after.phase - before.phase
  while (step > PI) step -= 2 * PI
  while (step <= -PI) step += 2 * PI

  val floor = QUIET_LEVEL * before.amplitude
  var run = 0
  var longest = 0
  for (frame in seam - near until seam + near) {
    run = if (abs(heard.samples[frame]) < floor) run + 1 else 0
    longest = maxOf(longest, run)
  }
  return SeamMeasure(
    quietRun = 1.seconds * longest / rate,
    phaseStep = (step / (2 * PI) / TONE_HZ).seconds,
  )
}

private class TonePhase(
  val phase: Double,
  val amplitude: Float,
)

// The phase of the tone over [length] frames from [from], on the clock of the whole run so two windows compare.
private fun phaseOver(
  samples: FloatArray,
  from: Int,
  length: Int,
  rate: Int,
): TonePhase {
  var inPhase = 0.0
  var quadrature = 0.0
  for (frame in from until from + length) {
    val angle = 2 * PI * TONE_HZ * frame / rate
    inPhase += samples[frame] * sin(angle)
    quadrature += samples[frame] * cos(angle)
  }
  return TonePhase(
    phase = atan2(quadrature, inPhase),
    amplitude = (2 * sqrt(inPhase * inPhase + quadrature * quadrature) / length).toFloat(),
  )
}

private fun frameOf(
  time: Duration,
  rate: Int,
): Int = (time.inWholeMicroseconds * rate / MICROS_PER_SECOND).toInt()

private const val PHASE_PERIODS = 16
private const val QUIET_LEVEL = 0.05f
private const val MICROS_PER_SECOND = 1_000_000L

// Far enough from the seam that a gap or a repeat sits between the windows, and not so far that a window leaves the
// clip.
private val PHASE_CLEARANCE = 25.milliseconds
