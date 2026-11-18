package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.test.NoisyPng
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * How long [pngSamples] takes on the noisy 480 by 480 picture, on the target the test runs on.
 *
 * The median of [RUNS] decodes after a warm up has to stay under [PNG_DECODE_GATE_MS]. The sixty frame run is printed
 * for reading and asserts nothing about time.
 */
class PngSamplesMeasureTest {
  @Test
  fun `a noisy picture decodes within the gate`() {
    val png = NoisyPng.png(0)
    repeat(WARM_UPS) { pngSamples(png).shouldNotBeNull() }
    val times = List(RUNS) { millis { pngSamples(png).shouldNotBeNull() } }.sorted()
    val median = (times[RUNS / 2 - 1] + times[RUNS / 2]) / 2

    println("pngSamples 480x480 median ${median.rounded()} ms, gate ${PNG_DECODE_GATE_MS.rounded()} ms")
    withClue("The median decode took ${median.rounded()} ms") { median shouldBeLessThan PNG_DECODE_GATE_MS }
  }

  @Test
  fun `sixty frames decode`() {
    val frames = NoisyPng.frames
    pngSamples(frames[0]).shouldNotBeNull()

    val total = millis { frames.forEach { pngSamples(it).shouldNotBeNull() } }

    println("pngSamples ${frames.size} frames ${total.rounded()} ms")
  }

  private inline fun millis(block: () -> Unit): Double {
    val start = TimeSource.Monotonic.markNow()
    block()
    return start.elapsedNow().inWholeMicroseconds / 1000.0
  }

  private fun Double.rounded(): Double = (this * 10).toLong() / 10.0

  private companion object {
    const val WARM_UPS = 20
    const val RUNS = 20
  }
}
