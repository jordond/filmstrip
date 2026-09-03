package dev.jordond.filmstrip.test

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tone measurement, pinned against signals whose answer is known before it runs.
 *
 * Every backend's mix test reads its levels through this, so it is measured here against synthesised
 * samples rather than against a file some encoder produced.
 */
class ToneAnalysisTest {
  @Test
  fun readsBackTheAmplitudeOfASingleTone() {
    val tone = sine(frequencyHz = 440, amplitude = 0.5f)

    val measured = ToneAnalysis.amplitudeOf(tone, 440, RATE)

    assertClose(0.5f, measured, "a half scale tone")
  }

  @Test
  fun tellsTwoToneStrengthsApartInOneMix() {
    val mixed = sine(frequencyHz = 440, amplitude = 0.8f) + sine(frequencyHz = 880, amplitude = 0.2f)

    assertClose(0.8f, ToneAnalysis.amplitudeOf(mixed, 440, RATE), "the loud tone")
    assertClose(0.2f, ToneAnalysis.amplitudeOf(mixed, 880, RATE), "the quiet tone")
  }

  @Test
  fun readsNothingAtAFrequencyTheSignalDoesNotCarry() {
    val tone = sine(frequencyHz = 440, amplitude = 1f)

    val measured = ToneAnalysis.amplitudeOf(tone, 880, RATE)

    assertTrue(measured < 0.02f, "a frequency the signal does not carry read $measured")
  }

  @Test
  fun measuresTheMiddleOfARampRatherThanItsEnds() {
    // A tone whose amplitude falls linearly from one to zero, so the middle reads a half and an
    // endpoint-only measurement would not notice the ramp running the wrong way.
    val faded =
      FloatArray(RATE) { index ->
        val progress = index.toFloat() / RATE
        (1f - progress) * sin(2.0 * PI * 440 * index / RATE).toFloat()
      }

    val middle = ToneAnalysis.amplitudeOver(faded, from = RATE / 2 - WINDOW / 2, length = WINDOW, 440, RATE)

    assertClose(0.5f, middle, "the middle of the ramp", tolerance = 0.03f)
  }

  @Test
  fun averagesInterleavedChannelsIntoOne() {
    val interleaved = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f)

    val mono = ToneAnalysis.toMono(interleaved, channels = 2)

    assertTrue(mono.size == 3, "three frames came back as ${mono.size}")
    assertClose(0.5f, mono[0], "the first frame")
    assertClose(0.5f, mono[1], "the second frame")
    assertClose(0f, mono[2], "the third frame")
  }

  private fun sine(
    frequencyHz: Int,
    amplitude: Float,
  ): FloatArray = FloatArray(RATE) { amplitude * sin(2.0 * PI * frequencyHz * it / RATE).toFloat() }

  private operator fun FloatArray.plus(other: FloatArray): FloatArray = FloatArray(size) { this[it] + other[it] }

  private fun assertClose(
    expected: Float,
    actual: Float,
    what: String,
    tolerance: Float = 0.01f,
  ) {
    assertTrue(abs(expected - actual) <= tolerance, "$what read $actual, expected about $expected")
  }

  private companion object {
    const val RATE = 48_000
    const val WINDOW = 4_800
  }
}
