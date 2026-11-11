package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The figures the kernel and every lowering read.
 */
class TapeAudioParamsTest {
  @Test
  fun `each speed plays its own figures at full strength`() {
    val expected =
      mapOf(
        TapeSpeed.SP to listOf(10_000.0, 43.0, 0.0015, 0.0010),
        TapeSpeed.LP to listOf(7_000.0, 41.0, 0.0025, 0.0015),
        TapeSpeed.EP to listOf(5_000.0, 39.0, 0.0035, 0.0020),
      )

    for ((tape, figures) in expected) {
      withClue(tape) {
        val params = tapeAudioParams(TapeAudio(tape = tape), RATE)
        params.bandLimited shouldBe true
        params.highPassHz shouldBe (100.0 plusOrMinus 1e-9)
        params.lowPassHz shouldBe (figures[0] plusOrMinus 1e-6)
        params.hissRms shouldBe (decibels(-figures[1]) plusOrMinus 1e-12)
        params.wowDepth shouldBe (figures[2] plusOrMinus 1e-12)
        params.wowRateHz shouldBe 0.8
        params.flutterDepth shouldBe (figures[3] plusOrMinus 1e-12)
        params.flutterRateHz shouldBe 9.0
        params.monoMix shouldBe 1.0
        params.hissPerChannel shouldBe false
      }
    }
  }

  @Test
  fun `a slower tape is worse on every figure`() {
    val sp = tapeAudioParams(TapeAudio(tape = TapeSpeed.SP), RATE)
    val lp = tapeAudioParams(TapeAudio(tape = TapeSpeed.LP), RATE)
    val ep = tapeAudioParams(TapeAudio(tape = TapeSpeed.EP), RATE)

    for ((better, worse) in listOf(sp to lp, lp to ep)) {
      worse.lowPassHz shouldBeLessThan better.lowPassHz
      worse.hissRms shouldBeGreaterThan better.hissRms
      worse.wowDepth shouldBeGreaterThan better.wowDepth
      worse.flutterDepth shouldBeGreaterThan better.flutterDepth
    }
  }

  @Test
  fun `half intensity halves the levels and moves each corner half way on a log scale`() {
    val full = tapeAudioParams(TapeAudio(), RATE)
    val half = tapeAudioParams(TapeAudio(intensity = 0.5f), RATE)

    half.hissRms shouldBe (full.hissRms / 2 plusOrMinus 1e-12)
    half.wowDepth shouldBe (full.wowDepth / 2 plusOrMinus 1e-12)
    half.flutterDepth shouldBe (full.flutterDepth / 2 plusOrMinus 1e-12)
    half.monoMix shouldBe 0.5
    half.lowPassHz shouldBe (sqrt(20_000.0 * 5_000.0) plusOrMinus 1e-6)
    half.highPassHz shouldBe (sqrt(5.0 * 100.0) plusOrMinus 1e-9)
    half.wowRateHz shouldBe full.wowRateHz
    half.flutterRateHz shouldBe full.flutterRateHz
  }

  @Test
  fun `a part at half scales its own figure and no other`() {
    val full = tapeAudioParams(TapeAudio(), RATE)

    val hiss = tapeAudioParams(TapeAudio(hiss = 0.5f), RATE)
    hiss.hissRms shouldBe (full.hissRms / 2 plusOrMinus 1e-12)
    hiss.lowPassHz shouldBe full.lowPassHz
    hiss.wowDepth shouldBe full.wowDepth

    val band = tapeAudioParams(TapeAudio(bandLimit = 0.5f), RATE)
    band.lowPassHz shouldBe (10_000.0 plusOrMinus 1e-6)
    band.hissRms shouldBe full.hissRms

    val wow = tapeAudioParams(TapeAudio(wow = Wobble(0.5f)), RATE)
    wow.wowDepth shouldBe (full.wowDepth / 2 plusOrMinus 1e-12)
    wow.flutterDepth shouldBe full.flutterDepth

    val flutter = tapeAudioParams(TapeAudio(flutter = Wobble(0.5f)), RATE)
    flutter.flutterDepth shouldBe (full.flutterDepth / 2 plusOrMinus 1e-12)
    flutter.wowDepth shouldBe full.wowDepth
  }

  @Test
  fun `a part and the intensity multiply`() {
    val full = tapeAudioParams(TapeAudio(), RATE)
    val both = tapeAudioParams(TapeAudio(intensity = 0.5f, hiss = 0.5f, wow = Wobble(0.5f)), RATE)

    both.hissRms shouldBe (full.hissRms / 4 plusOrMinus 1e-12)
    both.wowDepth shouldBe (full.wowDepth / 4 plusOrMinus 1e-12)
  }

  @Test
  fun `no band limit leaves the corners out and the hiss white`() {
    val params = tapeAudioParams(TapeAudio(bandLimit = 0f), RATE)

    params.bandLimited shouldBe false
    params.hissGain shouldBe (params.hissRms * sqrt(3.0) plusOrMinus 1e-12)
  }

  @Test
  fun `a wobble keeps its own rate and the swing follows it`() {
    val params = tapeAudioParams(TapeAudio(wow = Wobble(rateHz = 0.4f), flutter = Wobble(rateHz = 12f)), RATE)

    params.wowRateHz shouldBe 0.4
    params.flutterRateHz shouldBe 12.0
    params.wowFrames shouldBe (0.0035 * RATE / (2 * PI * 0.4) plusOrMinus 1e-9)
    params.flutterFrames shouldBe (0.0020 * RATE / (2 * PI * 12.0) plusOrMinus 1e-9)
  }

  @Test
  fun `the delay holds the full swing and does not move with strength`() {
    val full = tapeAudioParams(TapeAudio(), RATE)

    full.delayFrames.toDouble() shouldBeGreaterThan full.wowFrames + full.flutterFrames + 1.0
    full.delayFrames.toDouble() shouldBeLessThan full.wowFrames + full.flutterFrames + 3.0
    tapeAudioParams(TapeAudio(intensity = 0.3f, wow = Wobble(0.1f)), RATE).delayFrames shouldBe full.delayFrames
    tapeAudioParams(TapeAudio(wow = Wobble(rateHz = 0.4f)), RATE).delayFrames shouldBeGreaterThan full.delayFrames
    tapeAudioParams(TapeAudio(), 96_000).delayFrames shouldBeGreaterThan full.delayFrames
  }

  @Test
  fun `the slowest wobbles at the highest rate fit an Apple read-ahead`() {
    val slow = Wobble(rateHz = Wobble.MIN_RATE_HZ)
    val params = tapeAudioParams(TapeAudio(wow = slow, flutter = slow), 192_000)

    params.delayFrames shouldBeLessThan 3_584
  }

  @Test
  fun `the low-pass corner stops short of the Nyquist frequency`() {
    tapeAudioParams(TapeAudio(tape = TapeSpeed.SP), 16_000).lowPassHz shouldBe (7_200.0 plusOrMinus 1e-9)
    tapeAudioParams(TapeAudio(tape = TapeSpeed.EP), 8_000).lowPassHz shouldBe (3_600.0 plusOrMinus 1e-9)
    tapeAudioParams(TapeAudio(tape = TapeSpeed.EP), 16_000).lowPassHz shouldBe (5_000.0 plusOrMinus 1e-6)
  }

  private fun decibels(value: Double): Double = 10.0.pow(value / 20.0)

  private companion object {
    const val RATE = 48_000
  }
}
