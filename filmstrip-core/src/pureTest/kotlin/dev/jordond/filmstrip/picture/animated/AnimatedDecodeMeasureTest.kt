package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * How long the readers take on 60 frame 480 by 480 files, on the target the test runs on.
 *
 * A GIF frame has to decode within [GIF_DECODE_GATE_MS] at the median of [RUNS] reads after a warm up. The APNG read,
 * which is every frame through `pngSamples`, is printed for reading and asserts nothing about time.
 */
class AnimatedDecodeMeasureTest {
  @Test
  fun `a gif frame decodes within the gate`() {
    val gif = AnimatedGifs.noisy(Size(SIDE, SIDE), FRAMES).bytes
    repeat(WARM_UPS) { read(gif) }
    val times = List(RUNS) { millis { read(gif) } / FRAMES }.sorted()
    val median = (times[RUNS / 2 - 1] + times[RUNS / 2]) / 2

    println("gif ${SIDE}x$SIDE median ${median.rounded()} ms a frame, gate ${GIF_DECODE_GATE_MS.rounded()} ms")
    withClue("The median frame took ${median.rounded()} ms") { median shouldBeLessThan GIF_DECODE_GATE_MS }
  }

  @Test
  fun `sixty apng frames read through png samples`() {
    val apng = AnimatedApngs.noisy(FRAMES)
    read(apng)

    val total = millis { read(apng) }

    println("apng ${SIDE}x$SIDE $FRAMES frames ${total.rounded()} ms, ${(total / FRAMES).rounded()} ms a frame")
  }

  private fun read(bytes: ByteArray) {
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>()
  }

  private inline fun millis(block: () -> Unit): Double {
    val start = TimeSource.Monotonic.markNow()
    block()
    return start.elapsedNow().inWholeMicroseconds / 1000.0
  }

  private fun Double.rounded(): Double = (this * 100).toLong() / 100.0

  private companion object {
    const val SIDE = 480
    const val FRAMES = 60
    const val WARM_UPS = 5
    const val RUNS = 20
  }
}
