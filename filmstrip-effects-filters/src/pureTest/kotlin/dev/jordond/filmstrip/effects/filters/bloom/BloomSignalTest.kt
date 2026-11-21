package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The figures [BloomSignal] hands every lowering.
 */
class BloomSignalTest {
  @Test
  fun `sigmaOf runs from its floor to its ceiling and half way between at a half spread`() {
    BloomSignal.sigmaOf(0.0) shouldBe BLOOM_SIGMA_MIN
    BloomSignal.sigmaOf(0.5) shouldBe (BLOOM_SIGMA_MIN + BLOOM_SIGMA_REACH / 2.0 plusOrMinus TOLERANCE)
    BloomSignal.sigmaOf(1.0) shouldBe (BLOOM_SIGMA_MIN + BLOOM_SIGMA_REACH plusOrMinus TOLERANCE)
  }

  @Test
  fun `blurPlan lands on sigmaOf as a fraction of the frame`() {
    for (spread in listOf(0.0, 0.5, 1.0)) {
      for (width in listOf(640, 1366, 1920, 3840)) {
        val plan = BloomSignal.blurPlan(spread, width)

        plan.sigmaPx / plan.width shouldBe (BloomSignal.sigmaOf(spread) plusOrMinus TOLERANCE)
      }
    }
  }

  @Test
  fun `the plan blurs its own size at a factor of 1 with the same kernel`() {
    for (spread in listOf(0.0, 0.5, 1.0)) {
      for (width in listOf(640, 1366, 1920, 3840)) {
        val plan = BloomSignal.blurPlan(spread, width)
        val small = BlurPlan.of(BloomSignal.sigmaOf(spread), plan.width)

        small.factor shouldBe 1
        small.weights.toList() shouldBe plan.weights.toList()
      }
    }
  }

  @Test
  fun `the knee ends a knee above the threshold and holds at white`() {
    BloomSignal.kneeEnd(0.5) shouldBe (0.5 + BLOOM_KNEE plusOrMinus TOLERANCE)
    BloomSignal.kneeEnd(0.9) shouldBe 1.0
    BloomSignal.kneeEnd(1.0) shouldBe (1.0 + BLOOM_KNEE_FLOOR plusOrMinus TOLERANCE)
  }

  @Test
  fun `response keeps the colour and half of it half way up the knee`() {
    val threshold = 0.5
    val grey = threshold + BLOOM_KNEE / 2.0
    val tinted = doubleArrayOf(0.9, 0.6, 0.3)
    val luma = lumaOf(tinted[0], tinted[1], tinted[2])
    val t = (luma - threshold) / BLOOM_KNEE

    BloomSignal.response(grey, grey, grey, threshold).forEach { it shouldBe (grey / 2.0 plusOrMinus TOLERANCE) }
    val read = BloomSignal.response(tinted[0], tinted[1], tinted[2], threshold)
    for (channel in 0 until 3) read[channel] shouldBe (tinted[channel] * t * t * (3.0 - 2.0 * t) plusOrMinus TOLERANCE)
  }

  @Test
  fun `response gives nothing under the threshold and the whole colour past the knee`() {
    BloomSignal.response(0.4, 0.4, 0.4, 0.5).toList() shouldBe listOf(0.0, 0.0, 0.0)
    BloomSignal.response(0.8, 0.8, 0.8, 0.5).toList() shouldBe listOf(0.8, 0.8, 0.8)
    BloomSignal.response(1.0, 1.0, 1.0, 1.0).toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  @Test
  fun `bloomed screens the blurred light at mid gain`() {
    val params = BloomParams.at(0.5, 0.7, 0.5)

    val bloomed = BloomSignal.bloomed(0.25, 0.5, 0.75, doubleArrayOf(0.8, 0.4, 0.0), params)

    params.gain shouldBe (BLOOM_GAIN / 2.0 plusOrMinus TOLERANCE)
    bloomed[0] shouldBe (0.25 + params.gain * 0.8 * 0.75 plusOrMinus TOLERANCE)
    bloomed[1] shouldBe (0.5 + params.gain * 0.4 * 0.5 plusOrMinus TOLERANCE)
    bloomed[2] shouldBe 0.75
  }

  @Test
  fun `bloomed never passes white`() {
    val params = BloomParams.at(1.0, 0.0, 1.0)

    BloomSignal.bloomed(0.9, 1.0, 0.0, doubleArrayOf(5.0, 5.0, 5.0), params).toList() shouldBe listOf(1.0, 1.0, 1.0)
  }

  @Test
  fun `params carry the knee and the sigma of their controls`() {
    val params = BloomParams.of(Bloom(intensity = 0.5f, threshold = 0.5f, spread = 0.5f))

    params.threshold shouldBe 0.5
    params.kneeEnd shouldBe BloomSignal.kneeEnd(0.5)
    params.sigma shouldBe BloomSignal.sigmaOf(0.5)
  }

  @Test
  fun `the glow is blurPlane of the response on the blurred raster`() {
    val params = BloomParams.at(0.5, 0.4, 0.5)
    val plan = BloomSignal.blurPlan(0.5, FRAME_WIDTH)
    val height = plan.heightOf(FRAME_HEIGHT)
    // One blurred pixel per frame pixel is a frame the plan never boxes, so the glow's field is the response itself.
    val glow = BloomSignal.glow(plan.width, height, params) { x, y -> codesAt(x, y, plan.width, height) }

    val expected =
      List(3) { channel ->
        val plane =
          DoubleArray(plan.width * height) { index ->
            val codes = codesAt(index % plan.width, index / plan.width, plan.width, height)
            BloomSignal.response(codes[0], codes[1], codes[2], params.threshold)[channel]
          }
        blurPlane(plane, DoubleArray(plan.width * height) { 1.0 }, plan.width, height, params.sigma)
      }
    for (y in 0 until height step 5) {
      for (x in 0 until plan.width step 7) {
        val read = glow.at(x, y)
        for (channel in 0 until 3) read[channel] shouldBe (expected[channel][y * plan.width + x] plusOrMinus 1e-9)
      }
    }
    expected[0].max() shouldBeGreaterThan 0.5
  }

  // A ramp across with a different slope a channel, so the response runs through its knee and keeps a colour.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray {
    val across = x.toDouble() / (width - 1)
    val down = y.toDouble() / (height - 1)
    return doubleArrayOf(across, across * 0.8, down * 0.6)
  }

  private companion object {
    const val TOLERANCE = 1e-12
    const val FRAME_WIDTH = 1366
    const val FRAME_HEIGHT = 768
  }
}
