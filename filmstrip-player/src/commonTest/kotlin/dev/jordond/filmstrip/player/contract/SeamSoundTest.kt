package dev.jordond.filmstrip.player.contract

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * [measureSeam] on tones cut and joined by hand, so a seam the backends write is read by something that is known to see
 * a gap and a skip.
 */
class SeamSoundTest {
  @Test
  fun aContinuousToneReadsAsNoSeam() {
    val measured = measureSeam(tone(skipped = 0, silent = 0), SEAM)
    measured.phaseStep.toDouble(DurationUnit.MICROSECONDS) shouldBe (0.0.plusOrMinus(10.0))
    (measured.quietRun < 1.milliseconds) shouldBe true
  }

  @Test
  fun soundSkippedAtTheSeamReadsAsAPositiveStep() {
    val measured = measureSeam(tone(skipped = 24, silent = 0), SEAM)
    measured.phaseStep.toDouble(DurationUnit.MICROSECONDS) shouldBe (500.0.plusOrMinus(20.0))
  }

  @Test
  fun soundRepeatedAtTheSeamReadsAsANegativeStep() {
    val measured = measureSeam(tone(skipped = -24, silent = 0), SEAM)
    measured.phaseStep.toDouble(DurationUnit.MICROSECONDS) shouldBe ((-500.0).plusOrMinus(20.0))
  }

  @Test
  fun silenceAtTheSeamReadsAsAQuietRunOfItsLength() {
    val measured = measureSeam(tone(skipped = 0, silent = 480), SEAM)
    measured.quietRun.toDouble(DurationUnit.MILLISECONDS) shouldBe (10.0.plusOrMinus(0.5))
  }

  // A second of 440 Hz at 48 kHz, with [skipped] frames cut from it at the seam (repeated when negative) and [silent]
  // frames of silence put there.
  private fun tone(
    skipped: Int,
    silent: Int,
  ): HeardSound {
    val seam = (SEAM.inWholeMilliseconds * RATE / 1_000).toInt()
    val before = FloatArray(seam) { sin(2 * PI * TONE_HZ * it / RATE).toFloat() }
    val after = FloatArray(RATE / 2) { sin(2 * PI * TONE_HZ * (seam + skipped + it) / RATE).toFloat() }
    return HeardSound(before + FloatArray(silent) + after, RATE)
  }

  private companion object {
    const val RATE = 48_000
    val SEAM: Duration = 500.milliseconds
  }
}
