package dev.jordond.filmstrip.media

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class HdrSignalTest {
  @Test
  fun `a brightness factor moves display light by the display gamma rather than linearly`() {
    // The middle of the range, where a linear reading and a gamma one part company. A factor of
    // 0.5 read linearly would halve the light. It does not.
    brightnessDisplayGain(0.5f) shouldBeNear 0.217638f
    brightnessDisplayGain(0.75f) shouldBeNear 0.531049f
    brightnessDisplayGain(1.5f) shouldBeNear 2.44006f
    brightnessDisplayGain(2f) shouldBeNear 4.59479f

    assertTrue(brightnessDisplayGain(0.5f) < 0.5f, "the factor was applied to light without the gamma")
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
    assertTrue(
      brightnessSceneGain(0.5f) > brightnessDisplayGain(0.5f),
      "the scene gain was not lifted by the system gamma",
    )
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

    assertTrue(linearDimGain(0.5f) < 0.5f, "the gain was applied to linear light without the gamma")
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

  private infix fun Float.shouldBeNear(expected: Float) {
    val tolerance = if (abs(expected) > 1f) abs(expected) * 1e-3f else 1e-3f
    assertTrue(abs(this - expected) <= tolerance, "expected $expected but was $this")
  }
}
