package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What each tape format at each speed hands [TapePictureParams.at], and what comes out at the middle of the range.
 */
class PictureStocksTest {
  @Test
  fun `every VHS speed draws with the strengths of the VHS table`() {
    val expected =
      mapOf(
        TapeSpeed.SP to listOf(TapePictureSignal.sigmaFor(1.6), TapePictureSignal.sigmaFor(0.32)),
        TapeSpeed.LP to listOf(TapePictureSignal.sigmaFor(1.35), TapePictureSignal.sigmaFor(0.30)),
        TapeSpeed.EP to listOf(TapePictureSignal.sigmaFor(1.1), TapePictureSignal.sigmaFor(0.28)),
      )

    for ((speed, sigmas) in expected) {
      withClue(speed) {
        val params = TapePictureParams.of(Vhs(tape = speed))
        params.lumaSigma shouldBe sigmas[0]
        params.chromaSigma shouldBe sigmas[1]
        params.haloGain shouldBe 0.3
        params.jitter shouldBe 2.0E-4
        params.wave shouldBe 0.0015
        params.headShift shouldBe 0.1042
        params.chromaShift shouldBe 0.004
        params.lumaNoise shouldBe 0.008
        params.chromaNoise shouldBe 0.024
        params.dropoutChance shouldBe 2.0E-4
        params.dropoutLength shouldBe 0.03
        params.trackingRolls shouldBe false
        params.trackingChance shouldBe 0.2
        params.trackingPeriodTicks shouldBe 359.64
        params.trackingHeight shouldBe 0.07
        params.trackingShift shouldBe 0.02
        params.trackingNoise shouldBe 0.12
        params.trackingSnow shouldBe 0.6
        params.desaturation shouldBe 0.25
        params.lift shouldBe 0.03
      }
    }
  }

  @Test
  fun `a VHS at half intensity on EP blurs luma by half of its bandwidth's sigma`() {
    val half = TapePictureParams.of(Vhs(intensity = 0.5f, tape = TapeSpeed.EP))

    half.lumaSigma shouldBe (TapePictureSignal.sigmaFor(TapeSpeed.EP.lumaMHz) / 2 plusOrMinus 1e-15)
    half.chromaSigma shouldBe (TapePictureSignal.sigmaFor(TapeSpeed.EP.chromaMHz) / 2 plusOrMinus 1e-15)
  }

  @Test
  fun `each slower VHS speed blurs luma more than the one before it`() {
    val sigmas = listOf(TapeSpeed.SP, TapeSpeed.LP, TapeSpeed.EP).map { TapePictureParams.of(Vhs(tape = it)).lumaSigma }

    sigmas.shouldBeStrictlyIncreasing()
  }

  @Test
  fun `a Hi8 at half intensity draws half its stock's strengths and a quarter of its dropout chance`() {
    val stock = Hi8Speed.SP.pictureStock
    val vhs = TapePictureParams.of(Vhs())
    val half = TapePictureParams.of(Hi8(intensity = 0.5f))

    half.lumaSigma shouldBe (TapePictureSignal.sigmaFor(stock.lumaMHz) / 2 plusOrMinus 1e-15)
    half.chromaSigma shouldBe (TapePictureSignal.sigmaFor(stock.chromaMHz) / 2 plusOrMinus 1e-15)
    half.lumaNoise shouldBe (vhs.lumaNoise * stock.lumaNoise / 2 plusOrMinus 1e-15)
    half.chromaNoise shouldBe (vhs.chromaNoise * stock.chromaNoise / 2 plusOrMinus 1e-15)
    half.chromaShift shouldBe (vhs.chromaShift * stock.chromaShift / 2 plusOrMinus 1e-15)
    half.dropoutChance shouldBe (vhs.dropoutChance * stock.dropoutChance / 4 plusOrMinus 1e-15)
    half.dropoutLength shouldBe stock.dropoutLength
    half.headShift shouldBe (vhs.headShift / 2 plusOrMinus 1e-15)
    half.lift shouldBe (vhs.lift / 2 plusOrMinus 1e-15)
  }

  @Test
  fun `a Betamax at a mid intensity scales its own stock`() {
    val stock = BetaSpeed.III.pictureStock
    val vhs = TapePictureParams.of(Vhs())
    val params = TapePictureParams.of(Betamax(intensity = 0.5f, tape = BetaSpeed.III))

    params.lumaSigma shouldBe (TapePictureSignal.sigmaFor(stock.lumaMHz) / 2 plusOrMinus 1e-15)
    params.lumaNoise shouldBe (vhs.lumaNoise * stock.lumaNoise / 2 plusOrMinus 1e-15)
    params.chromaShift shouldBe (vhs.chromaShift * stock.chromaShift / 2 plusOrMinus 1e-15)
    params.dropoutChance shouldBe (vhs.dropoutChance * stock.dropoutChance / 4 plusOrMinus 1e-15)
    params.dropoutLength shouldBe stock.dropoutLength
  }

  @Test
  fun `the formats differ where their tapes do`() {
    val vhs = TapePictureParams.of(Vhs(tape = TapeSpeed.SP))
    val betamax = TapePictureParams.of(Betamax(tape = BetaSpeed.I))
    val hi8 = TapePictureParams.of(Hi8(tape = Hi8Speed.SP))

    (betamax.lumaSigma < vhs.lumaSigma) shouldBe true
    (betamax.lumaNoise < vhs.lumaNoise) shouldBe true
    (hi8.lumaSigma < betamax.lumaSigma) shouldBe true
    (hi8.dropoutChance > vhs.dropoutChance) shouldBe true
    (hi8.chromaNoise < vhs.chromaNoise) shouldBe true
    (hi8.dropoutLength < vhs.dropoutLength) shouldBe true
  }

  @Test
  fun `Hi8 blurs luma less than Betamax and Betamax less than VHS at matching speeds`() {
    val matching =
      listOf(
        listOf(Hi8(tape = Hi8Speed.SP), Betamax(tape = BetaSpeed.I), Vhs(tape = TapeSpeed.SP)),
        listOf(Hi8(tape = Hi8Speed.LP), Betamax(tape = BetaSpeed.II), Vhs(tape = TapeSpeed.LP)),
        listOf(Betamax(tape = BetaSpeed.III), Vhs(tape = TapeSpeed.EP)),
      )

    for (looks in matching) {
      withClue(looks.map { it::class.simpleName }) {
        looks.map { TapePictureParams.of(it).lumaSigma }.shouldBeStrictlyIncreasing()
      }
    }
  }

  @Test
  fun `each slower speed of a format blurs luma more than the one before it`() {
    val betamax = BetaSpeed.entries.map { TapePictureParams.of(Betamax(tape = it)).lumaSigma }
    val hi8 = Hi8Speed.entries.map { TapePictureParams.of(Hi8(tape = it)).lumaSigma }

    betamax.shouldBeStrictlyIncreasing()
    hi8.shouldBeStrictlyIncreasing()
  }

  @Test
  fun `a dropout runs its stock's length and reads a half in the middle of its rise and of its fall`() {
    for (params in listOf(TapePictureParams.of(Hi8()), TapePictureParams.of(Vhs()))) {
      withClue("dropout length ${params.dropoutLength}") {
        val (tick, row) = dropoutIn(params)
        val start = FilterNoise.unorm(FilterNoise.hash24(SEED, PASS_DROP_POS, tick, row))
        val draw = FilterNoise.unorm(FilterNoise.hash24(SEED, PASS_DROP_LEN, tick, row))
        val length = params.dropoutLength * (0.4 + 1.2 * draw)

        TapePictureSignal.dropout(SEED, tick, row, start + 0.05 * length, params) shouldBe (0.5 plusOrMinus 1e-9)
        TapePictureSignal.dropout(SEED, tick, row, start + 0.35 * length, params) shouldBe (1.0 plusOrMinus 1e-9)
        TapePictureSignal.dropout(SEED, tick, row, start + 0.8 * length, params) shouldBe (0.5 plusOrMinus 1e-9)
        TapePictureSignal.dropout(SEED, tick, row, start + 1.01 * length, params) shouldBe 0.0
      }
    }
  }

  // The first tick and row that hold a dropout which ends inside the frame.
  private fun dropoutIn(params: TapePictureParams): Pair<Int, Int> {
    for (tick in 0 until TICKS) {
      for (row in 0 until VIRTUAL_ROWS) {
        val fires = FilterNoise.unorm(FilterNoise.hash24(SEED, PASS_DROP_EVENT, tick, row)) < params.dropoutChance
        val start = FilterNoise.unorm(FilterNoise.hash24(SEED, PASS_DROP_POS, tick, row))
        if (fires && start < 0.9) return tick to row
      }
    }
    error("no dropout in $TICKS ticks")
  }

  private companion object {
    const val SEED = 1984
    const val TICKS = 600
  }
}
