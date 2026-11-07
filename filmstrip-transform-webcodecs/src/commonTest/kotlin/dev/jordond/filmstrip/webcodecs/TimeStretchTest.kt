package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.webcodecs.internal.audio.TimeStretch
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class TimeStretchTest {
  @Test
  fun aToneKeepsItsPitchLengthAndLevelAtEverySpeed() {
    SPEEDS.forEach { speed ->
      val input = toneOf(TONE_HZ, sourceFramesFor(speed), RATE)
      val expected = expectedFrames(input.size, speed)

      val output = stretch(input, speed, expected)

      withClue("at speed $speed") {
        output.size shouldBe expected
        output.dominantHz(RATE) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
        output.rms().shouldBeWithinPercentageOf(input.rms(), RMS_PERCENT)
      }
    }
  }

  // A tone in phase across every grain leaves no step between one sample and the next larger than the tone's own.
  @Test
  fun aToneRunsWithoutAClickAtEverySpeed() {
    SPEEDS.forEach { speed ->
      val input = toneOf(TONE_HZ, sourceFramesFor(speed), RATE)

      val output = stretch(input, speed, expectedFrames(input.size, speed))

      val largest = (1 until output.size).maxOf { abs(output[it] - output[it - 1]) }
      withClue("at speed $speed the largest step was $largest") { (largest < MAX_STEP) shouldBe true }
    }
  }

  @Test
  fun speedOneReturnsTheInputUntouched() {
    val input = toneOf(TONE_HZ, RATE, RATE)

    stretch(input, 1.0, input.size).toList() shouldBe input.toList()
  }

  @Test
  fun speedOnePadsPastTheEndOfTheInputWithSilence() {
    val input = toneOf(TONE_HZ, HOP_SIZED, RATE)

    val output = stretch(input, 1.0, input.size + PAD_FRAMES)

    output.size shouldBe input.size + PAD_FRAMES
    output.toList().drop(input.size).all { it == 0f } shouldBe true
  }

  // Every grain reads only source it waited for, so how the input and the pulls were cut cannot move a sample. The
  // stated tolerance is zero.
  @Test
  fun slicingTheInputAndThePullsGivesTheSameOutputAsOnePass() {
    SPEEDS.forEach { speed ->
      val input = chirpOf(sourceFramesFor(speed))
      val expected = expectedFrames(input.size, speed)
      val whole = stretch(input, speed, expected)

      val sliced = stretchSliced(input, speed, expected, pushFrames = 777, pullFrames = 1_013)

      withClue("at speed $speed") { sliced.toList() shouldBe whole.toList() }
    }
  }

  @Test
  fun twoRunsGiveTheSameOutput() {
    val input = chirpOf(RATE)

    val first = stretch(input, MIDDLE_SPEED, expectedFrames(input.size, MIDDLE_SPEED))
    val second = stretch(input, MIDDLE_SPEED, expectedFrames(input.size, MIDDLE_SPEED))

    first.toList() shouldBe second.toList()
  }

  @Test
  fun everyChannelKeepsItsOwnSignal() {
    val left = toneOf(TONE_HZ, RATE, RATE)
    val right = toneOf(RIGHT_HZ, RATE, RATE)
    val frames = expectedFrames(left.size, MIDDLE_SPEED)
    val stretch = TimeStretch(MIDDLE_SPEED, 2, RATE, left.size.toLong())

    stretch.push(arrayOf(left, right), left.size)
    stretch.finish()
    val output = stretch.pull(frames)

    output[0].dominantHz(RATE) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
    output[1].dominantHz(RATE) shouldBe (RIGHT_HZ plusOrMinus DOMINANT_STEP_HZ)
  }

  // A source that stops short of what it promised runs out into silence instead of repeating its last grain.
  @Test
  fun aSourceThatStopsEarlyRunsOutIntoSilence() {
    val input = toneOf(TONE_HZ, RATE / 2, RATE)
    val stretch = TimeStretch(MIDDLE_SPEED, 1, RATE, RATE.toLong())

    stretch.push(arrayOf(input), input.size)
    stretch.finish()
    val output = stretch.pull(expectedFrames(RATE, MIDDLE_SPEED))[0]

    output.rms(from = output.size - SILENT_TAIL) shouldBe (0.0 plusOrMinus SILENCE_RMS)
  }

  // At a high speed most of the source sits between grains, and a caller is told so it can move past it uncopied.
  @Test
  fun skippedInputGivesTheSameOutputAsCopiedInput() {
    val speed = 32.0
    val input = chirpOf(sourceFramesFor(speed))
    val expected = expectedFrames(input.size, speed)
    val whole = stretch(input, speed, expected)

    val stretch = TimeStretch(speed, 1, RATE, input.size.toLong())
    var fed = 0
    var skipped = 0L
    while (!stretch.canPull(expected)) {
      val count = minOf(PUSH_FRAMES, input.size - fed)
      if (count == 0) {
        stretch.finish()
      } else if (count <= stretch.skippableFrames) {
        stretch.skip(count)
        skipped += count
        fed += count
      } else {
        stretch.push(arrayOf(input), count, offset = fed)
        fed += count
      }
    }

    stretch.pull(expected)[0].toList() shouldBe whole.toList()
    withClue("nothing was skipped at speed $speed") { (skipped > 0L) shouldBe true }
  }

  private fun stretch(
    input: FloatArray,
    speed: Double,
    frames: Int,
  ): FloatArray {
    val stretch = TimeStretch(speed, 1, RATE, input.size.toLong())
    stretch.push(arrayOf(input), input.size)
    stretch.finish()
    return stretch.pull(frames)[0]
  }

  private fun stretchSliced(
    input: FloatArray,
    speed: Double,
    frames: Int,
    pushFrames: Int,
    pullFrames: Int,
  ): FloatArray {
    val stretch = TimeStretch(speed, 1, RATE, input.size.toLong())
    val output = FloatArray(frames)
    var fed = 0
    var pulled = 0
    while (pulled < frames) {
      val want = minOf(pullFrames, frames - pulled)
      while (!stretch.canPull(want)) {
        val count = minOf(pushFrames, input.size - fed)
        if (count == 0) {
          stretch.finish()
        } else {
          stretch.push(arrayOf(input), count, offset = fed)
          fed += count
        }
      }
      stretch.pull(want)[0].copyInto(output, pulled)
      pulled += want
    }
    return output
  }

  // A rising sweep, so a grain read from the wrong place reads a different frequency and shows up in a comparison.
  private fun chirpOf(frames: Int): FloatArray =
    FloatArray(frames) {
      val seconds = it.toDouble() / RATE
      (0.5 * sin(2.0 * PI * (CHIRP_FROM_HZ * seconds + CHIRP_RATE * seconds * seconds))).toFloat()
    }

  // Enough source that the output runs long enough to read a pitch from, whichever way the speed goes.
  private fun sourceFramesFor(speed: Double): Int = (RATE * maxOf(speed / 4.0, 0.25)).roundToInt()

  private fun expectedFrames(
    sourceFrames: Int,
    speed: Double,
  ): Int {
    val length: Duration = (sourceFrames.toDouble() / RATE).seconds
    return (outputLengthOf(length, speed).toDouble(DurationUnit.SECONDS) * RATE).roundToInt()
  }

  private companion object {
    const val RATE = 48_000
    const val TONE_HZ = 440.0
    const val RIGHT_HZ = 660.0
    const val MIDDLE_SPEED = 0.7
    const val RMS_PERCENT = 5.0
    const val MAX_STEP = 0.05f
    const val HOP_SIZED = 2_048
    const val PAD_FRAMES = 500
    const val SILENT_TAIL = 1_000
    const val SILENCE_RMS = 1e-6
    const val PUSH_FRAMES = 1_024
    const val CHIRP_FROM_HZ = 200.0
    const val CHIRP_RATE = 150.0
    val SPEEDS = listOf(1.0 / 32.0, MIDDLE_SPEED, 1.0, 1.5, 32.0)
  }
}
