package dev.jordond.filmstrip.transform.internal.hdr

import dev.jordond.filmstrip.color.HLG_A
import dev.jordond.filmstrip.color.HLG_B
import dev.jordond.filmstrip.color.HLG_C
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test

class SdrToneCurveTest {
  private val tolerance = 1e-6

  @Test
  fun `PQ grey below the knee passes through at its own level`() {
    val out = toneMappedSdr(HdrTransfer.Pq, pqGrey(200.0))

    listOf(out.r, out.g, out.b).forEach { it shouldBe (sdr(200.0) plusOrMinus tolerance) }
  }

  @Test
  fun `the mastering peak and anything past it lands on the SDR peak`() {
    pqKneeNits(TONE_MAP_MASTERING_PEAK_NITS) shouldBe (TONE_MAP_SDR_PEAK_NITS plusOrMinus tolerance)
    pqKneeNits(4000.0) shouldBe (TONE_MAP_SDR_PEAK_NITS plusOrMinus tolerance)
    toneMappedSdr(HdrTransfer.Pq, pqGrey(1000.0)).g shouldBe (1.0 plusOrMinus tolerance)
  }

  @Test
  fun `the first Hermite piece is read at 500 nits and at its middle`() {
    listOf(500.0, (PqKnee.x1 + PqKnee.x2) / 2.0).forEach { nits ->
      val t = (nits - PqKnee.x1) / PqKnee.h12
      val expected = hermite(t, PqKnee.y1, PqKnee.y2, PqKnee.m1, PqKnee.m2, PqKnee.h12)

      withClue("$nits nits") {
        pqKneeNits(nits) shouldBe (expected plusOrMinus tolerance)
        toneMappedSdr(HdrTransfer.Pq, pqGrey(nits)).g shouldBe (sdr(expected) plusOrMinus tolerance)
      }
    }
  }

  @Test
  fun `the second Hermite piece is read at 800 nits and at its middle`() {
    listOf(800.0, (PqKnee.x2 + TONE_MAP_MASTERING_PEAK_NITS) / 2.0).forEach { nits ->
      val t = (nits - PqKnee.x2) / PqKnee.h23
      val expected = hermite(t, PqKnee.y2, TONE_MAP_SDR_PEAK_NITS, PqKnee.m2, PqKnee.m3, PqKnee.h23)

      withClue("$nits nits") {
        pqKneeNits(nits) shouldBe (expected plusOrMinus tolerance)
        toneMappedSdr(HdrTransfer.Pq, pqGrey(nits)).g shouldBe (sdr(expected) plusOrMinus tolerance)
      }
    }
  }

  @Test
  fun `the knee is continuous at both joins and keeps one slope where the Hermite pieces meet`() {
    val step = 1e-6

    pqKneeNits(PqKnee.x1 - step) shouldBe (PqKnee.y1 plusOrMinus 1e-4)
    pqKneeNits(PqKnee.x1) shouldBe (PqKnee.y1 plusOrMinus tolerance)
    pqKneeNits(PqKnee.x2 - step) shouldBe (PqKnee.y2 plusOrMinus 1e-4)
    pqKneeNits(PqKnee.x2) shouldBe (PqKnee.y2 plusOrMinus tolerance)

    val wide = 1e-3
    val below = (pqKneeNits(PqKnee.x2) - pqKneeNits(PqKnee.x2 - wide)) / wide
    val above = (pqKneeNits(PqKnee.x2 + wide) - pqKneeNits(PqKnee.x2)) / wide
    below shouldBe (PqKnee.m2 plusOrMinus 1e-4)
    above shouldBe (PqKnee.m2 plusOrMinus 1e-4)
  }

  @Test
  fun `HLG grey at mid signals raises scene light by the system gamma`() {
    listOf(0.5 to 0.5 * 0.5 / 3.0, 0.75 to (exp((0.75 - HLG_C) / HLG_A) + HLG_B) / 12.0).forEach { (signal, scene) ->
      val out = toneMappedSdr(HdrTransfer.Hlg, Rgb(signal, signal, signal))
      val expected = scene.pow(TONE_MAP_HLG_GAMMA).pow(1.0 / TONE_MAP_OETF_GAMMA)

      withClue(
        "signal $signal",
      ) { listOf(out.r, out.g, out.b).forEach { it shouldBe (expected plusOrMinus tolerance) } }
    }
  }

  @Test
  fun `an off grey PQ colour scales every channel by the gain its green takes on the knee`() {
    val nits = Rgb(300.0, 500.0, 100.0)
    val t = (nits.g - PqKnee.x1) / PqKnee.h12
    val greenOut = hermite(t, PqKnee.y1, PqKnee.y2, PqKnee.m1, PqKnee.m2, PqKnee.h12)
    val expected = TONE_MAP_BT2020_TO_BT709 * (nits * (greenOut / nits.g / TONE_MAP_SDR_PEAK_NITS))

    val out = toneMappedSdr(HdrTransfer.Pq, nits.map(::pqSignalFromNits))

    out.r shouldBe (expected.r.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
    out.g shouldBe (expected.g.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
    out.b shouldBe (expected.b.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
    withClue("red at 300 nits passes the knee alone, so a per channel curve would land it higher") {
      sdr(300.0) shouldBeGreaterThan out.r
    }
  }

  @Test
  fun `an off grey HLG colour scales every channel by one luminance gain`() {
    val signal = Rgb(0.7, 0.55, 0.3)
    val scene = signal.map { if (it <= 0.5) it * it / 3.0 else (exp((it - HLG_C) / HLG_A) + HLG_B) / 12.0 }
    val luminance = scene dot TONE_MAP_LUMA_WEIGHTS
    val expected = TONE_MAP_BT2020_TO_BT709 * (scene * luminance.pow(TONE_MAP_HLG_GAMMA - 1.0))

    val out = toneMappedSdr(HdrTransfer.Hlg, signal)

    out.r shouldBe (expected.r.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
    out.g shouldBe (expected.g.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
    out.b shouldBe (expected.b.pow(1.0 / TONE_MAP_OETF_GAMMA) plusOrMinus tolerance)
  }

  @Test
  fun `BT2020 colour outside BT709 clamps onto the edge after the matrix`() {
    val green = toneMappedSdr(HdrTransfer.Pq, Rgb(0.0, pqSignalFromNits(300.0), 0.0))
    green.r shouldBe 0.0
    green.b shouldBe 0.0
    green.g shouldBe (sdr(300.0 * TONE_MAP_BT2020_TO_BT709.green.g) plusOrMinus tolerance)

    val red = toneMappedSdr(HdrTransfer.Pq, Rgb(pqSignalFromNits(600.0), pqSignalFromNits(100.0), 0.0))
    red.r shouldBe 1.0
  }

  @Test
  fun `the BT2020 to BT709 matrix keeps D65 grey grey`() {
    val grey = TONE_MAP_BT2020_TO_BT709 * Rgb(1.0, 1.0, 1.0)

    listOf(grey.r, grey.g, grey.b).forEach { it shouldBe (1.0 plusOrMinus 1e-7) }
  }

  private fun pqGrey(nits: Double): Rgb = pqSignalFromNits(nits).let { Rgb(it, it, it) }

  private fun sdr(nits: Double): Double = (nits / TONE_MAP_SDR_PEAK_NITS).pow(1.0 / TONE_MAP_OETF_GAMMA)

  // The textbook basis form of a cubic Hermite piece, not the factored form the curve is written in.
  private fun hermite(
    t: Double,
    p0: Double,
    p1: Double,
    m0: Double,
    m1: Double,
    width: Double,
  ): Double {
    val t2 = t * t
    val t3 = t2 * t

    return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * width * m0 + (-2 * t3 + 3 * t2) * p1 +
      (t3 - t2) * width * m1
  }
}
