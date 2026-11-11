package dev.jordond.filmstrip.effects.filters.tape

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The hiss and the bend as functions of the timeline frame.
 */
class TapeAudioSignalTest {
  @Test
  fun `a hiss draw depends on the seed the channel and the frame alone`() {
    tapeNoiseAt(1984, 0, 48_000) shouldBe tapeNoiseAt(1984, 0, 48_000)
    tapeNoiseAt(1984, 0, 48_000) shouldNotBe tapeNoiseAt(1985, 0, 48_000)
    tapeNoiseAt(1984, 0, 48_000) shouldNotBe tapeNoiseAt(1984, 1, 48_000)
    tapeNoiseAt(1984, 0, 48_000) shouldNotBe tapeNoiseAt(1984, 0, 48_001)
  }

  @Test
  fun `hiss does not repeat past 24 bits of frames`() {
    val wrap = 1L shl 24
    val same = (0L until 1_000L).count { tapeNoiseAt(1984, 0, it) == tapeNoiseAt(1984, 0, it + wrap) }

    same shouldBe 0
  }

  @Test
  fun `hiss is spread evenly either side of silence`() {
    var sum = 0.0
    var squares = 0.0
    for (frame in 0L until COUNT) {
      val draw = tapeNoiseAt(1984, 0, frame)
      draw shouldBeGreaterThanOrEqual -1.0
      draw shouldBeLessThan 1.0
      sum += draw
      squares += draw * draw
    }

    sum / COUNT shouldBe (0.0 plusOrMinus 0.01)
    sqrt(squares / COUNT) shouldBe (1.0 / sqrt(3.0) plusOrMinus 0.005)
  }

  @Test
  fun `the delay stays inside the swing around its centre`() {
    val params = tapeAudioParams(TapeAudio(), RATE)
    val reach = params.wowFrames + params.flutterFrames
    var furthest = 0.0

    for (frame in 0L until RATE * 3L) {
      val off = abs(tapeDelayAt(params, frame) - params.delayFrames)
      off shouldBeLessThanOrEqual reach
      furthest = maxOf(furthest, off)
    }

    furthest shouldBeGreaterThanOrEqual reach * 0.9
  }

  @Test
  fun `a wow at half depth bends the pitch by half the tape's figure`() {
    val params = tapeAudioParams(TapeAudio(wow = Wobble(0.5f), flutter = Wobble(0f)), RATE)

    steepest(params, RATE * 2L) shouldBe (params.wowDepth plusOrMinus params.wowDepth * 0.001)
    params.wowDepth shouldBe (0.00175 plusOrMinus 1e-12)
  }

  @Test
  fun `a flutter at half depth bends the pitch by half the tape's figure`() {
    val params = tapeAudioParams(TapeAudio(wow = Wobble(0f), flutter = Wobble(0.5f)), RATE)

    steepest(params, RATE.toLong()) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * 0.001)
    params.flutterDepth shouldBe (0.001 plusOrMinus 1e-12)
  }

  @Test
  fun `each wobble repeats at its rate`() {
    val wow = tapeAudioParams(TapeAudio(flutter = Wobble(0f)), RATE)
    val flutter = tapeAudioParams(TapeAudio(wow = Wobble(0f), flutter = Wobble(rateHz = 10f)), RATE)
    val wowPeriod = (RATE / wow.wowRateHz).roundToLong()
    val flutterPeriod = (RATE / flutter.flutterRateHz).roundToLong()

    for (frame in 0L until 20_000L step 997) {
      tapeDelayAt(wow, frame + wowPeriod) shouldBe (tapeDelayAt(wow, frame) plusOrMinus 1e-6)
      tapeDelayAt(wow, frame + wowPeriod / 2) shouldNotBe (tapeDelayAt(wow, frame) plusOrMinus 1e-3)
      tapeDelayAt(flutter, frame + flutterPeriod) shouldBe (tapeDelayAt(flutter, frame) plusOrMinus 1e-6)
    }
  }

  @Test
  fun `the seed picks where the wobbles start`() {
    val one = tapeAudioParams(TapeAudio(seed = 1), RATE)
    val two = tapeAudioParams(TapeAudio(seed = 2), RATE)

    tapeDelayAt(one, 0) shouldBe tapeDelayAt(tapeAudioParams(TapeAudio(seed = 1), RATE), 0)
    tapeDelayAt(one, 0) shouldNotBe (tapeDelayAt(two, 0) plusOrMinus 1e-3)
  }

  @Test
  fun `the bend keeps its shape an hour in`() {
    val params = tapeAudioParams(TapeAudio(wow = Wobble(0f), flutter = Wobble(rateHz = 10f)), RATE)
    val hour = RATE * 3_600L
    val period = (RATE / params.flutterRateHz).roundToLong()

    tapeDelayAt(params, hour + period) shouldBe (tapeDelayAt(params, hour) plusOrMinus 1e-6)
  }

  @Test
  fun `the noise gain of the low-pass is what white noise measures through it`() {
    val sections = tapeAudioParams(TapeAudio(), RATE).lowPass
    val state = DoubleArray(sections.size * 2)
    var into = 0.0
    var out = 0.0
    for (frame in 0L until COUNT) {
      val draw = tapeNoiseAt(7, 0, frame)
      var sample = draw
      sections.forEachIndexed { index, section -> sample = section.step(sample, state, index * 2) }
      into += draw * draw
      out += sample * sample
    }

    noiseGainOf(sections) shouldBe (sqrt(out / into) plusOrMinus 0.01)
  }

  // The largest change in delay from one frame to the next, which is the largest change in pitch.
  private fun steepest(
    params: TapeAudioParams,
    frames: Long,
  ): Double {
    var most = 0.0
    var last = tapeDelayAt(params, 0)
    for (frame in 1L until frames) {
      val delay = tapeDelayAt(params, frame)
      most = maxOf(most, abs(delay - last))
      last = delay
    }
    return most
  }

  private companion object {
    const val RATE = 48_000
    const val COUNT = 200_000L
  }
}
