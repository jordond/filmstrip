package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test

class HdrSignalTest {
  @Test
  fun `a brightness factor moves display light by the display gamma rather than linearly`() {
    // The middle of the range, where a linear reading and a gamma one part company. A factor of
    // 0.5 read linearly would halve the light. It does not.
    brightnessDisplayGain(0.5f) shouldBeNear 0.217638f
    brightnessDisplayGain(0.75f) shouldBeNear 0.531049f
    brightnessDisplayGain(1.5f) shouldBeNear 2.44006f
    brightnessDisplayGain(2f) shouldBeNear 4.59479f

    withClue("the factor was applied to light without the gamma") {
      (brightnessDisplayGain(0.5f) < 0.5f).shouldBeTrue()
    }
  }

  @Test
  fun `the ends of the brightness range are still the ends`() {
    brightnessDisplayGain(0f) shouldBeNear 0f
    brightnessDisplayGain(1f) shouldBeNear 1f
    brightnessSceneGain(0f) shouldBeNear 0f
    brightnessSceneGain(1f) shouldBeNear 1f
  }

  @Test
  fun `a scene gain raised by the system gamma is the display gain`() {
    listOf(0.25f, 0.5f, 0.75f, 1.5f, 2f).forEach { factor ->
      val roundTripped = brightnessSceneGain(factor).toDouble().pow(HLG_SYSTEM_GAMMA).toFloat()

      roundTripped shouldBeNear brightnessDisplayGain(factor)
    }
  }

  @Test
  fun `a scene gain sits above the display gain it produces when the frame is darkened`() {
    // Scaling scene light by s moves display light by s^1.2, so a dimming scene gain has to be the
    // larger of the two. A backend that handed the display gain to an HLG pipeline would darken
    // this much too far.
    withClue("the scene gain was not lifted by the system gamma") {
      (brightnessSceneGain(0.5f) > brightnessDisplayGain(0.5f)).shouldBeTrue()
    }
  }

  @Test
  fun `a brightness past where the gain overflows is held at the largest float`() {
    listOf(1e18f, Float.MAX_VALUE).forEach { factor ->
      withClue("factor $factor") {
        brightnessDisplayGain(factor) shouldBe Float.MAX_VALUE
        brightnessSceneGain(factor).isFinite().shouldBeTrue()
      }
    }
    brightnessDisplayGain(2f) shouldBe 2.0.pow(SDR_DISPLAY_GAMMA).toFloat()
  }

  @Test
  fun `a negative factor is read as black and never as a NaN`() {
    brightnessDisplayGain(-1f) shouldBeNear 0f
    brightnessSceneGain(-1f) shouldBeNear 0f
  }

  @Test
  fun `dimming linear light is a gamma apart from dimming an encoded one`() {
    linearDimGain(0.5f) shouldBeNear 0.217638f
    linearDimGain(0.25f) shouldBeNear 0.531049f

    withClue("the gain was applied to linear light without the gamma") { (linearDimGain(0.5f) < 0.5f).shouldBeTrue() }
  }

  @Test
  fun `the ends of the dim range are still the ends`() {
    linearDimGain(0f) shouldBeNear 1f
    linearDimGain(1f) shouldBeNear 0f
  }

  @Test
  fun `a PQ signal decodes back to the light it was encoded from`() {
    listOf(1f, 100f, HDR_REFERENCE_WHITE_NITS, 1_000f, PQ_PEAK_NITS).forEach { nits ->
      nitsFromPqSignal(pqSignalFromNits(nits)) shouldBeNear nits
    }
  }

  @Test
  fun `a PQ reference white is the code value the transfer function defines for it`() {
    // The one figure here with a source outside this file, so it is what says the transfer is
    // right and not just self consistent.
    pqSignalFromNits(HDR_REFERENCE_WHITE_NITS) shouldBeNear 0.580689f
  }

  @Test
  fun `an HLG signal decodes back to the scene light it was encoded from`() {
    listOf(0.001f, 0.01f, 1f / 12f, 0.25f, 0.5f, 1f).forEach { scene ->
      sceneFromHlgSignal(hlgSignalFromScene(scene)) shouldBeNear scene
    }
  }

  @Test
  fun `the two halves of the HLG transfer meet where it says they do`() {
    // The knee is at a scene value of 1/12, and a curve whose halves disagree there shows up as a
    // band in a gradient rather than as anything a round trip would catch.
    hlgSignalFromScene(1f / 12f) shouldBeNear 0.5f
    sceneFromHlgSignal(0.5f) shouldBeNear 1f / 12f
  }

  @Test
  fun `an HLG peak is the full signal`() {
    hlgSignalFromScene(1f) shouldBeNear 1f
    hlgSignalFromScene(0f) shouldBeNear 0f
  }

  @Test
  fun `an SDR signal decodes back to the light it was encoded from`() {
    listOf(1f, 44f, 100f, HDR_REFERENCE_WHITE_NITS, 1_000f, PQ_PEAK_NITS).forEach { nits ->
      nitsFromSdrSignal(sdrSignalFromNits(nits)) shouldBeNear nits
    }
  }

  @Test
  fun `reference white is one and the signal is not clamped above it`() {
    sdrSignalFromNits(HDR_REFERENCE_WHITE_NITS) shouldBeNear 1f
    // The middle of the range, where a linear reading and a gamma one part company.
    sdrSignalFromNits(100f) shouldBeNear 0.724818f
    withClue("the signal was clamped at white") { (sdrSignalFromNits(1_000f) > 1f).shouldBeTrue() }
    nitsFromSdrSignal(-0.5f) shouldBeNear 0f
  }

  @Test
  fun `a brightness factor on the signal is the display gain on the light`() {
    listOf(0.25f, 0.5f, 0.75f, 1.5f, 2f).forEach { factor ->
      listOf(10f, 100f, 500f).forEach { nits ->
        val throughSignal = nitsFromSdrSignal(factor * sdrSignalFromNits(nits))

        throughSignal shouldBeNear brightnessDisplayGain(factor) * nits
      }
    }
  }

  @Test
  fun `the signal ceiling is where each format runs out`() {
    HdrTransfer.Pq.peakNits shouldBeNear PQ_PEAK_NITS
    HdrTransfer.Hlg.peakNits shouldBeNear HLG_NOMINAL_PEAK_NITS
    HdrTransfer.Pq.sdrSignalCeiling shouldBeNear 5.87922f
    HdrTransfer.Hlg.sdrSignalCeiling shouldBeNear 2.06431f
    nitsFromSdrSignal(HdrTransfer.Pq.sdrSignalCeiling) shouldBeNear PQ_PEAK_NITS
  }

  @Test
  fun `HLG scene light decodes back through the per channel opto-optical transfer`() {
    listOf(0.01f, 0.1f, 0.25f, 0.5f, 1f).forEach { scene ->
      hlgSceneFromDisplayNits(hlgDisplayNitsFromScene(scene)) shouldBeNear scene
      hlgSceneFromSdrSignal(sdrSignalFromHlgScene(scene)) shouldBeNear scene
    }
    hlgDisplayNitsFromScene(1f) shouldBeNear HLG_NOMINAL_PEAK_NITS
    hlgDisplayNitsFromScene(0.5f) shouldBeNear 435.275f
  }

  @Test
  fun `a brightness factor on the signal is the scene gain on HLG scene light`() {
    listOf(0.5f, 1.5f).forEach { factor ->
      listOf(0.1f, 0.3f).forEach { scene ->
        val throughSignal = hlgSceneFromSdrSignal(factor * sdrSignalFromHlgScene(scene))

        throughSignal shouldBeNear brightnessSceneGain(factor) * scene
      }
    }
  }

  @Test
  fun `the BT2020 luminance weights sum to one`() {
    BT2020_LUMA_R + BT2020_LUMA_G + BT2020_LUMA_B shouldBeNear 1f
  }

  // A chroma channel spans plus or minus a half, so its scale is twice the distance the channel it
  // is built from travels from luma. Deriving them keeps the shader that reads them off the weights
  // rather than off a typed pair of decimals.
  @Test
  fun `the chroma scales follow from the luminance weights`() {
    BT2020_CB_SCALE shouldBeNear 1.8814f
    BT2020_CR_SCALE shouldBeNear 1.4746f
  }

  // A grey carries no chroma, so each chroma row's weights cancel and luma's sum to one. The two sets of rows undo each
  // other, which a typo in either one breaks on the off-diagonal entries.
  @Test
  fun `the BT2020 rows encode and decode as one matrix`() {
    BT2020_ENCODE_ROWS[0].sum() shouldBeNear 1.0
    BT2020_ENCODE_ROWS[1].sum() shouldBeNear 0.0
    BT2020_ENCODE_ROWS[2].sum() shouldBeNear 0.0
    repeat(3) { row ->
      repeat(3) { column ->
        val product = (0 until 3).sumOf { BT2020_DECODE_ROWS[row][it] * BT2020_ENCODE_ROWS[it][column] }
        product shouldBeNear if (row == column) 1.0 else 0.0
      }
    }
  }

  @Test
  fun `pure red and blue land on the chroma extremes`() {
    BT2020_ENCODE_ROWS[2][0] shouldBeNear 0.5
    BT2020_ENCODE_ROWS[1][2] shouldBeNear 0.5
  }

  @Test
  fun `the chroma shift moves Cb and Cr from the chroma middle to black and leaves luma`() {
    val fromBlack = (TEN_BIT_CHROMA_MID - TEN_BIT_LUMA_FLOOR).toDouble() / TEN_BIT_CHROMA_RANGE
    val shifted = BT2020_ENCODE_ROWS.map { row -> (0 until 3).sumOf { row[it] * BT2020_CHROMA_SHIFT[it] } }

    shifted[0] shouldBeNear 0.0
    shifted[1] shouldBeNear fromBlack
    shifted[2] shouldBeNear fromBlack
  }

  // A saturated mid-range color, where HLG's luminance-driven transfer and a per channel reading of it part company.
  @Test
  fun `a signal decodes back to the light it was encoded from on either transfer`() {
    val color = floatArrayOf(180f, 420f, 35f)

    HdrTransfer.entries.forEach { transfer ->
      val back = transfer.nitsFromSignal(transfer.signalFromNits(color))

      repeat(3) { back[it] shouldBeNear color[it] }
    }
    val signal = HdrTransfer.Hlg.signalFromNits(color)
    val perChannel = FloatArray(3) { hlgDisplayNitsFromSignal(signal[it]) }
    withClue("a per channel reading lands on ${perChannel[2]}") {
      (abs(perChannel[2] - color[2]) > color[2] * 0.1f).shouldBeTrue()
    }
  }

  // The rows are built on Float weights and scales, which agree with each other to a Float's precision.
  private fun ChannelTriple.sum(): Double = (0 until 3).sumOf { this[it] }

  private infix fun Double.shouldBeNear(expected: Double) {
    withClue("expected $expected but was $this") { (abs(this - expected) <= 1e-7).shouldBeTrue() }
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    val tolerance = if (abs(expected) > 1f) abs(expected) * 1e-3f else 1e-3f
    withClue("expected $expected but was $this") { (abs(this - expected) <= tolerance).shouldBeTrue() }
  }
}
