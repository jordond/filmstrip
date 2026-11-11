package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.export.AudioFormat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The filters a [TapeAudio] is written as, against [tapeAudioParams].
 */
class TapeAudioLoweringTest {
  @Test
  fun `the default tape bends then narrows the band with the hiss ahead of the low-pass`() {
    tapeAudioFilters(TapeAudio(), MONO).map { it.name } shouldBe
      listOf("adelay", "apad", "vibrato", "vibrato", "atrim", "highpass", "aeval", "lowpass", "lowpass")
  }

  @Test
  fun `a tape at the middle of every range reads its figures from the params`() {
    val spec = TapeAudio(0.5f, TapeSpeed.LP, 7, 0.5f, 0.5f, Wobble(0.5f, 1.5f), Wobble(0.5f, 12f))
    val params = tapeAudioParams(spec, RATE)
    val nodes = tapeAudioFilters(spec, MONO)
    val reach = (vibratoBufferFrames(RATE) - 1) / 2.0

    val (wow, flutter) = nodes.filter { it.name == "vibrato" }
    wow["f"].toDouble() shouldBe params.wowRateHz
    wow["d"].toDouble() shouldBe (params.wowFrames / reach plusOrMinus 1e-12)
    flutter["f"].toDouble() shouldBe params.flutterRateHz
    flutter["d"].toDouble() shouldBe (params.flutterFrames / reach plusOrMinus 1e-12)

    val highPass = nodes.single { it.name == "highpass" }
    highPass["f"].toDouble() shouldBe params.highPassHz
    highPass["w"].toDouble() shouldBe HIGH_PASS_Q
    val lowPass = nodes.filter { it.name == "lowpass" }
    lowPass.map { it["f"].toDouble() } shouldBe listOf(params.lowPassHz, params.lowPassHz)
    lowPass.map { it["w"].toDouble() } shouldBe LOW_PASS_Q
    (nodes.filter { it.name.endsWith("pass") }.map { it["p"] to it["t"] }).toSet() shouldBe setOf("2" to "q")

    val hiss = nodes.single { it.name == "aeval" }["exprs"]
    hiss shouldContain "val(ch)+${params.hissGain.toBigDecimal().toPlainString()}*ld(3)"
    hiss shouldContain "st(0,7);"
    hiss shouldContain "floor(t*$RATE+0.5)"
  }

  @Test
  fun `the bends are cut back off by the frames they hold`() {
    val spec = TapeAudio(wow = Wobble(0.5f))
    val bends = vibratoBendsOf(tapeAudioParams(spec, RATE))
    val lead = vibratoBufferFrames(RATE)
    val held = heldFramesOf(bends, RATE)
    val nodes = tapeAudioFilters(spec, MONO)

    held.toDouble() shouldBe (2 * lead - bends.sumOf { it.depth } * (lead - 1) / 2.0 plusOrMinus 0.5)
    nodes.single { it.name == "adelay" }["delays"] shouldBe "${lead}S"
    nodes.single { it.name == "apad" }["pad_len"] shouldBe "$held"
    nodes.single { it.name == "atrim" }["start_sample"] shouldBe "${lead + held}"
    nodes.single { it.name == "aeval" }["exprs"] shouldContain "floor(t*$RATE+0.5)-${lead + held})"
  }

  @Test
  fun `one bend holds one buffer less its swing`() {
    val spec = TapeAudio(flutter = Wobble(0f))
    val bends = vibratoBendsOf(tapeAudioParams(spec, RATE))
    val nodes = tapeAudioFilters(spec, MONO)

    bends.size shouldBe 1
    nodes.count { it.name == "vibrato" } shouldBe 1
    heldFramesOf(bends, RATE).toDouble() shouldBe (240 - tapeAudioParams(spec, RATE).wowFrames plusOrMinus 0.5)
  }

  @Test
  fun `a tape with no bend is exact and writes no vibrato`() {
    val spec = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))

    tapeAudioResolution(spec).shouldBeInstanceOf<AudioEffectResolution.Resolved>()
    tapeAudioFilters(spec, MONO).map { it.name } shouldBe listOf("highpass", "aeval", "lowpass", "lowpass")
    tapeAudioFilters(spec, MONO).single { it.name == "aeval" }["exprs"] shouldContain "floor(t*$RATE+0.5)-0)"
  }

  @Test
  fun `a tape with a bend is an approximation`() {
    for (spec in listOf(TapeAudio(), TapeAudio(wow = Wobble(0f)), TapeAudio(flutter = Wobble(0f)))) {
      val resolution = tapeAudioResolution(spec).shouldBeInstanceOf<AudioEffectResolution.Degraded>()
      resolution.causes.map { it.reason } shouldBe listOf(DegradationReason.ApproximateAlgorithm)
    }
  }

  @Test
  fun `a bend past the reach of vibrato is played at its most`() {
    val slow = TapeAudio(wow = Wobble(rateHz = Wobble.MIN_RATE_HZ))
    val params = tapeAudioParams(slow, RATE)

    (vibratoBufferFrames(RATE) - 1) / 2.0 shouldBeLessThan params.wowFrames
    vibratoBendsOf(params).first().depth shouldBe 1.0
    tapeAudioFilters(slow, MONO).first { it.name == "vibrato" }["d"] shouldBe "1.0"
  }

  @Test
  fun `the buffer is rounded the way vibrato rounds it`() {
    mapOf(48_000 to 240, 44_100 to 221, 88_200 to 442, 22_050 to 111, 96_000 to 480, 8_000 to 40).forEach {
      vibratoBufferFrames(it.key) shouldBe it.value
    }
  }

  @Test
  fun `stereo folds toward the mean of both channels and shares one hiss`() {
    val nodes = tapeAudioFilters(TapeAudio(intensity = 0.5f), STEREO)

    nodes.first().name shouldBe "aeval"
    nodes.first()["exprs"] shouldBe "val(ch)+0.5*((val(0)+val(1))/2-val(ch))"
    nodes.last { it.name == "aeval" }["exprs"] shouldNotContain "+ch)"
    tapeAudioFilters(TapeAudio(), MONO).count { it.name == "aeval" } shouldBe 1
  }

  @Test
  fun `a stereo tape is not folded and draws a hiss for each channel`() {
    val nodes = tapeAudioFilters(TapeAudio(mono = false), STEREO)

    nodes.count { it.name == "aeval" } shouldBe 1
    nodes.single { it.name == "aeval" }["exprs"] shouldContain "mod(($PASS_HISS+ch),"
  }

  @Test
  fun `a part at nothing writes no filter`() {
    tapeAudioFilters(TapeAudio(bandLimit = 0f), MONO).map { it.name } shouldNotContain "lowpass"
    tapeAudioFilters(TapeAudio(bandLimit = 0f), MONO).map { it.name } shouldNotContain "highpass"
    tapeAudioFilters(TapeAudio(hiss = 0f), MONO).map { it.name } shouldNotContain "aeval"
    tapeAudioFilters(TapeAudio(bandLimit = 0f), MONO).map { it.name } shouldContain "aeval"
  }

  @Test
  fun `the low-pass stops short of the Nyquist frequency of the stream`() {
    val nodes = tapeAudioFilters(TapeAudio(tape = TapeSpeed.SP), AudioFormat(16_000, 1))

    nodes.last { it.name == "lowpass" }["f"].toDouble() shouldBe
      tapeAudioParams(TapeAudio(tape = TapeSpeed.SP), 16_000).lowPassHz
    nodes.last { it.name == "lowpass" }["f"].toDouble() shouldBe 7_200.0
    nodes.single { it.name == "adelay" }["delays"] shouldBe "80S"
  }

  @Test
  fun `no figure is written with an exponent`() {
    val nodes = tapeAudioFilters(TapeAudio(intensity = 0.01f, hiss = 0.01f, wow = Wobble(0.01f)), MONO)

    nodes.filter { it.name != "aeval" }.flatMap { it.arguments }.forEach { it.value shouldNotContain "E" }
    nodes.single { it.name == "aeval" }["exprs"] shouldNotContain "E-"
  }

  private operator fun FilterNode.get(key: String): String = arguments.single { it.key == key }.value

  private companion object {
    const val RATE = 48_000
    val MONO = AudioFormat(RATE, 1)
    val STEREO = AudioFormat(RATE, 2)
  }
}
