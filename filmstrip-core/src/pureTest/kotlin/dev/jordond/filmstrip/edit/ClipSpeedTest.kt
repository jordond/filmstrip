package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.InternalFilmstripApi
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalFilmstripApi::class)
class ClipSpeedTest {
  private val speeds = listOf(CLIP_SPEED_RANGE.start, 0.7, 1.0, 1.5, CLIP_SPEED_RANGE.endInclusive)

  private fun Duration.nanosFrom(other: Duration): Long = abs((this - other).inWholeNanoseconds)

  // One rounding on the way out, scaled by speed on the way back, plus one on the way back itself.
  private fun roundTripBound(speed: Double): Long = ceil(speed).toLong() + 1

  @Test
  fun `a source length round trips through its output length at every speed`() {
    speeds.forEach { speed ->
      val source = 7.seconds + 123_456_789.nanoseconds
      sourceLengthOf(outputLengthOf(source, speed), speed).nanosFrom(source) shouldBeLessThanOrEqual
        roundTripBound(speed)
    }
  }

  @Test
  fun `an output length reads the source length divided by the speed in the middle of the range`() {
    outputLengthOf(3.seconds, 1.5) shouldBe 2.seconds
    outputLengthOf(7.seconds, 0.7) shouldBe 10.seconds
    sourceLengthOf(2.seconds, 1.5) shouldBe 3.seconds
    sourceLengthOf(10.seconds, 0.7) shouldBe 7.seconds
  }

  @Test
  fun `the range ends scale by thirty two`() {
    outputLengthOf(1.seconds, CLIP_SPEED_RANGE.start) shouldBe 32.seconds
    outputLengthOf(32.seconds, CLIP_SPEED_RANGE.endInclusive) shouldBe 1.seconds
    sourceLengthOf(32.seconds, CLIP_SPEED_RANGE.start) shouldBe 1.seconds
    sourceLengthOf(1.seconds, CLIP_SPEED_RANGE.endInclusive) shouldBe 32.seconds
  }

  @Test
  fun `a speed of one is the identity with no rounding`() {
    // Past what a Double holds to the nanosecond, so any trip through one would move it.
    val awkward = 2_600.hours + 1.nanoseconds
    awkward.inWholeNanoseconds.toDouble().toLong() shouldNotBe awkward.inWholeNanoseconds
    outputLengthOf(awkward, 1.0) shouldBe awkward
    sourceLengthOf(awkward, 1.0) shouldBe awkward
    sourceOffsetAt(awkward, 1.0) shouldBe awkward
  }

  @Test
  fun `a long clip keeps its rounding to the nanosecond bound`() {
    val source = 24.hours + 333_333_333.nanoseconds
    speeds.forEach { speed ->
      sourceLengthOf(outputLengthOf(source, speed), speed).nanosFrom(source) shouldBeLessThanOrEqual
        roundTripBound(speed)
    }
  }

  @Test
  fun `a source offset scales the output offset by the speed`() {
    sourceOffsetAt(1.seconds, 0.7) shouldBe 700.milliseconds
    sourceOffsetAt(1.seconds, 1.5) shouldBe 1500.milliseconds
    sourceOffsetAt(Duration.ZERO, 0.7) shouldBe Duration.ZERO
  }

  @Test
  fun `an audio length rounds to the nearest frame through the middle of the range`() {
    audioFramesOf(outputLengthOf(1.seconds, 0.7), 48_000) shouldBe 68_571L
    audioFramesOf(outputLengthOf(1.seconds, 1.5), 48_000) shouldBe 32_000L
    audioFramesOf(500.milliseconds, 44_100) shouldBe 22_050L
    audioFramesOf(10.microseconds, 48_000) shouldBe 0L
    audioFramesOf(11.microseconds, 48_000) shouldBe 1L
    audioFramesOf(Duration.ZERO, 48_000) shouldBe 0L
  }

  @Test
  fun `an audio length in seconds counts the frames its duration does`() {
    audioFramesOf(0.7, 48_000) shouldBe audioFramesOf(0.7.seconds, 48_000)
    audioFramesOf(1.00003125, 48_000) shouldBe audioFramesOf(1.00003125.seconds, 48_000)
    audioFramesOf(0.5, 44_100) shouldBe audioFramesOf(500.milliseconds, 44_100)
  }

  @Test
  fun `the range is valid through both ends`() {
    speeds.forEach { isValidClipSpeed(it).shouldBeTrue() }
  }

  @Test
  fun `a speed nothing can play is not valid`() {
    listOf(
      0.0,
      -1.0,
      Double.NaN,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      CLIP_SPEED_RANGE.start.nextDown(),
      CLIP_SPEED_RANGE.endInclusive.nextUp(),
    ).forEach { isValidClipSpeed(it).shouldBeFalse() }
  }
}
