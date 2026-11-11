package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * What the kernel does to a stream, measured against [tapeAudioParams].
 */
class TapeAudioKernelTest {
  @Test
  fun `equal seeds play the same samples and another seed does not`() {
    val input = tone(1_000.0, RATE / 2)

    val first = play(TapeAudio(seed = 7), input)
    first.toList() shouldBe play(TapeAudio(seed = 7), input).toList()
    first.toList() shouldNotBe play(TapeAudio(seed = 8), input).toList()
  }

  @Test
  fun `any split of the stream plays the same samples`() {
    val input = tone(440.0, RATE / 2, channels = 2)
    val whole = play(TapeAudio(mono = false), input, channels = 2).toList()

    for (block in listOf(1, 7, 480, 4_096)) {
      withClue("blocks of $block") {
        play(TapeAudio(mono = false), input, channels = 2, block = block).toList() shouldBe whole
      }
    }
  }

  @Test
  fun `a stream started late plays what the whole stream settles to`() {
    val input = tone(440.0, RATE)
    val whole = play(TapeAudio(), input)
    val from = RATE / 2
    val late = play(TapeAudio(), input.copyOfRange(from, input.size), start = from.toLong())

    val settle = RATE / 10
    for (frame in settle until late.size) {
      abs(late[frame] - whole[from + frame]).toDouble() shouldBeLessThan 1e-4
    }
  }

  @Test
  fun `reset plays as a new kernel at that position would`() {
    val params = tapeAudioParams(TapeAudio(), RATE)
    val input = tone(440.0, RATE / 4)
    val used = TapeAudioKernel(params, 1)
    used.process(input.copyOf(), input.size, 0)
    used.reset(96_000)
    val again = input.copyOf().also { used.process(it, it.size, 96_000) }
    val fresh = input.copyOf().also { TapeAudioKernel(params, 1).process(it, it.size, 96_000) }

    again.toList() shouldBe fresh.toList()
  }

  @Test
  fun `with every part off the stream leaves late by the latency and otherwise untouched`() {
    val off = TapeAudio(bandLimit = 0f, hiss = 0f, wow = Wobble(0f), flutter = Wobble(0f), mono = false)
    val kernel = TapeAudioKernel(tapeAudioParams(off, RATE), 2)
    val input = tone(440.0, 4_800, channels = 2)
    val output = input.copyOf().also { kernel.process(it, 4_800, 0) }

    val latency = kernel.latencyFrames
    latency shouldBe tapeAudioParams(off, RATE).delayFrames
    for (frame in 0 until latency) output[frame * 2] shouldBe 0f
    for (index in latency * 2 until output.size) output[index] shouldBe input[index - latency * 2]
  }

  @Test
  fun `each speed passes the middle of its band and is 3 dB down at both corners`() {
    for (tape in TapeSpeed.entries) {
      val spec = quiet(TapeAudio(tape = tape))
      val params = tapeAudioParams(spec, RATE)
      withClue(tape) {
        gainAt(spec, sqrt(params.highPassHz * params.lowPassHz)) shouldBe (1.0 plusOrMinus 0.01)
        gainAt(spec, params.highPassHz) shouldBe (CORNER plusOrMinus 0.01)
        gainAt(spec, params.lowPassHz) shouldBe (CORNER plusOrMinus 0.01)
        gainAt(spec, params.lowPassHz * 2) shouldBeLessThan 0.08
        gainAt(spec, params.highPassHz / 2) shouldBeLessThan 0.26
      }
    }
  }

  @Test
  fun `a band limit at half puts the corners where the params say`() {
    val spec = quiet(TapeAudio(bandLimit = 0.5f))
    val params = tapeAudioParams(spec, RATE)

    gainAt(spec, params.lowPassHz) shouldBe (CORNER plusOrMinus 0.01)
    gainAt(spec, params.highPassHz) shouldBe (CORNER plusOrMinus 0.01)
    gainAt(spec, tapeAudioParams(TapeAudio(), RATE).lowPassHz) shouldBe (1.0 plusOrMinus 0.05)
  }

  @Test
  fun `no band limit passes the whole band`() {
    val spec = quiet(TapeAudio(bandLimit = 0f))

    gainAt(spec, 30.0) shouldBe (1.0 plusOrMinus 0.001)
    gainAt(spec, 15_000.0) shouldBe (1.0 plusOrMinus 0.02)
  }

  @Test
  fun `a wow at half depth bends a tone by half the tape's figure at the wow's rate`() {
    for (tape in TapeSpeed.entries) {
      val spec = TapeAudio(tape = tape, bandLimit = 0f, hiss = 0f, wow = Wobble(0.5f), flutter = Wobble(0f))
      val params = tapeAudioParams(spec, RATE)
      val cycles = 4
      val bend = bendOf(spec, seconds = cycles / params.wowRateHz)

      withClue(tape) {
        bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * 0.02)
        bend.steepest shouldBe (params.wowDepth plusOrMinus params.wowDepth * 0.05)
        bend.depthAt(params.wowRateHz * 1.5) shouldBeLessThan params.wowDepth * 0.2
      }
    }
  }

  @Test
  fun `a flutter at half depth bends a tone by half the tape's figure at the flutter's rate`() {
    for (tape in TapeSpeed.entries) {
      val spec = TapeAudio(tape = tape, bandLimit = 0f, hiss = 0f, wow = Wobble(0f), flutter = Wobble(0.5f))
      val params = tapeAudioParams(spec, RATE)
      val cycles = 18
      val bend = bendOf(spec, seconds = cycles / params.flutterRateHz)

      withClue(tape) {
        bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * 0.02)
        bend.steepest shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * 0.05)
        bend.depthAt(params.flutterRateHz * 1.5) shouldBeLessThan params.flutterDepth * 0.2
      }
    }
  }

  @Test
  fun `wow and flutter together bend a tone by each one's depth`() {
    val spec = TapeAudio(bandLimit = 0f, hiss = 0f, wow = Wobble(rateHz = 1f), flutter = Wobble(rateHz = 9f))
    val params = tapeAudioParams(spec, RATE)
    val bend = bendOf(spec, seconds = 4.0)

    bend.depthAt(1.0) shouldBe (params.wowDepth plusOrMinus params.wowDepth * 0.02)
    bend.depthAt(9.0) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * 0.02)
  }

  @Test
  fun `silence leaves as hiss at each speed's level`() {
    for (tape in TapeSpeed.entries) {
      val params = tapeAudioParams(TapeAudio(tape = tape), RATE)

      withClue(tape) { hissOf(TapeAudio(tape = tape)) shouldBe (params.hissRms plusOrMinus params.hissRms * 0.03) }
    }
  }

  @Test
  fun `hiss at half is half as loud and no hiss is silence`() {
    val full = tapeAudioParams(TapeAudio(), RATE).hissRms

    hissOf(TapeAudio(hiss = 0.5f)) shouldBe (full / 2 plusOrMinus full * 0.015)
    hissOf(TapeAudio(intensity = 0.5f)) shouldBe (full / 2 plusOrMinus full * 0.015)
    hissOf(TapeAudio(hiss = 0f)) shouldBe 0.0
  }

  @Test
  fun `hiss keeps its level with no band limit`() {
    val spec = TapeAudio(bandLimit = 0f)

    hissOf(spec) shouldBe (tapeAudioParams(spec, RATE).hissRms plusOrMinus tapeAudioParams(spec, RATE).hissRms * 0.03)
  }

  @Test
  fun `hiss sits inside the band`() {
    val spec = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
    val params = tapeAudioParams(spec, RATE)
    val output = play(spec, FloatArray(RATE * 2))

    val inside = amplitude(output, RATE / 2, output.size, params.lowPassHz / 2)
    val outside = amplitude(output, RATE / 2, output.size, params.lowPassHz * 3)
    outside shouldBeLessThan inside * 0.1
  }

  @Test
  fun `mono folds the channels to one sound and shares one hiss`() {
    val input = FloatArray(RATE) { if (it % 2 == 0) sin(2 * PI * 440.0 * (it / 2) / RATE).toFloat() * 0.5f else 0f }
    val output = play(TapeAudio(), input, channels = 2)

    for (frame in 0 until RATE / 2) output[frame * 2] shouldBe output[frame * 2 + 1]
    amplitude(left(output), RATE / 4, RATE / 2, 440.0) shouldBe (0.25 plusOrMinus 0.01)
  }

  @Test
  fun `stereo keeps the channels apart and hisses each on its own`() {
    val input = FloatArray(RATE) { if (it % 2 == 0) sin(2 * PI * 440.0 * (it / 2) / RATE).toFloat() * 0.5f else 0f }
    val output = play(TapeAudio(mono = false), input, channels = 2)

    amplitude(left(output), RATE / 4, RATE / 2, 440.0) shouldBe (0.5 plusOrMinus 0.01)
    val right = FloatArray(RATE / 2) { output[it * 2 + 1] }
    amplitude(right, RATE / 4, RATE / 2, 440.0) shouldBeLessThan 0.01
    val silence = play(TapeAudio(mono = false), FloatArray(RATE), channels = 2)
    (0 until RATE / 2).count { silence[it * 2] == silence[it * 2 + 1] } shouldBeLessThan 10
  }

  @Test
  fun `half intensity folds the channels half way`() {
    val input = FloatArray(RATE) { if (it % 2 == 0) sin(2 * PI * 440.0 * (it / 2) / RATE).toFloat() * 0.5f else 0f }
    val spec = TapeAudio(intensity = 0.5f, hiss = 0f, wow = Wobble(0f), flutter = Wobble(0f))
    val output = play(spec, input, channels = 2)

    val right = FloatArray(RATE / 2) { output[it * 2 + 1] }
    amplitude(left(output), RATE / 4, RATE / 2, 440.0) shouldBe (0.375 plusOrMinus 0.005)
    amplitude(right, RATE / 4, RATE / 2, 440.0) shouldBe (0.125 plusOrMinus 0.005)
  }

  private fun quiet(spec: TapeAudio): TapeAudio =
    TapeAudio(spec.intensity, spec.tape, spec.seed, spec.bandLimit, hiss = 0f, Wobble(0f), Wobble(0f), spec.mono)

  private fun left(stereo: FloatArray): FloatArray = FloatArray(stereo.size / 2) { stereo[it * 2] }

  private fun tone(
    hz: Double,
    frames: Int,
    channels: Int = 1,
  ): FloatArray =
    FloatArray(frames * channels) {
      (LEVEL * sin(2 * PI * hz * (it / channels) / RATE) * if (it % channels == 0) 1.0 else 0.5).toFloat()
    }

  private fun play(
    spec: TapeAudio,
    input: FloatArray,
    channels: Int = 1,
    block: Int = input.size / channels,
    start: Long = 0,
  ): FloatArray {
    val kernel = TapeAudioKernel(tapeAudioParams(spec, RATE), channels)
    val output = FloatArray(input.size)
    val buffer = FloatArray(block * channels)
    var frame = 0
    val frames = input.size / channels
    while (frame < frames) {
      val count = minOf(block, frames - frame)
      input.copyInto(buffer, 0, frame * channels, (frame + count) * channels)
      kernel.process(buffer, count, start + frame)
      buffer.copyInto(output, frame * channels, 0, count * channels)
      frame += count
    }
    return output
  }

  // How much of a steady tone at hz gets through, as a ratio of amplitudes, once the filters have settled.
  private fun gainAt(
    spec: TapeAudio,
    hz: Double,
  ): Double {
    val output = play(spec, tone(hz, RATE * 2))
    return amplitude(output, RATE, output.size, hz) / LEVEL
  }

  // The amplitude of the sine at hz that fits samples best between two frames.
  private fun amplitude(
    samples: FloatArray,
    from: Int,
    until: Int,
    hz: Double,
  ): Double {
    var ss = 0.0
    var cc = 0.0
    var sc = 0.0
    var ys = 0.0
    var yc = 0.0
    for (frame in from until until) {
      val s = sin(2 * PI * hz * frame / RATE)
      val c = cos(2 * PI * hz * frame / RATE)
      ss += s * s
      cc += c * c
      sc += s * c
      ys += samples[frame] * s
      yc += samples[frame] * c
    }
    val det = ss * cc - sc * sc
    return hypot((ys * cc - yc * sc) / det, (yc * ss - ys * sc) / det)
  }

  private fun hissOf(spec: TapeAudio): Double {
    val output = play(spec, FloatArray(RATE * 3))
    var squares = 0.0
    for (frame in RATE / 2 until output.size) squares += output[frame].toDouble() * output[frame]
    return sqrt(squares / (output.size - RATE / 2))
  }

  // Plays a steady tone through spec and reads how its pitch moves, one reading for each period of the tone, over
  // the given seconds after a lead-in.
  private fun bendOf(
    spec: TapeAudio,
    seconds: Double,
  ): Bend {
    val period = (RATE / CARRIER).roundToInt()
    val lead = RATE / 4 / period
    val readings = (seconds * RATE / period).roundToInt()
    val output = play(spec, tone(CARRIER, (lead + readings + 2) * period))
    val turn = 2 * PI * CARRIER / RATE
    val delays = DoubleArray(readings + 1)
    var last = 0.0
    var wraps = 0.0
    for (reading in 0..readings) {
      var inPhase = 0.0
      var quadrature = 0.0
      val first = (lead + reading) * period
      for (frame in first until first + period) {
        inPhase += output[frame] * cos(turn * frame)
        quadrature += output[frame] * sin(turn * frame)
      }
      val phase = atan2(-inPhase, quadrature)
      if (reading > 0 && phase - last > PI) wraps -= 2 * PI
      if (reading > 0 && phase - last < -PI) wraps += 2 * PI
      last = phase
      delays[reading] = (phase + wraps) / turn
    }
    return Bend(DoubleArray(readings) { (delays[it + 1] - delays[it]) / period }, period.toDouble() / RATE)
  }

  // The change in pitch at each reading, as a fraction of the pitch.
  private class Bend(
    val pitch: DoubleArray,
    val step: Double,
  ) {
    val steepest: Double get() = pitch.maxOf { abs(it) }

    // How much of the bend repeats at hz.
    fun depthAt(hz: Double): Double {
      var real = 0.0
      var imaginary = 0.0
      pitch.forEachIndexed { index, value ->
        real += value * cos(2 * PI * hz * index * step)
        imaginary += value * sin(2 * PI * hz * index * step)
      }
      return 2 * hypot(real, imaginary) / pitch.size
    }
  }

  private companion object {
    const val RATE = 48_000
    const val LEVEL = 0.5
    const val CARRIER = 1_000.0
    const val CORNER = 0.7071
  }
}
