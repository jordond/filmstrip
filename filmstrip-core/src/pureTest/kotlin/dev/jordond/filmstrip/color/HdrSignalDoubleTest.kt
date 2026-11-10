package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test

class HdrSignalDoubleTest {
  @Test
  fun `the SDR transfers round their double definition once`() {
    grid(0f, PQ_PEAK_NITS).forEach { nits ->
      sdrSignalFromNits(nits) shouldRound sdrSignalFromNits(nits.toDouble())
    }
    grid(0f, HdrTransfer.Pq.sdrSignalCeiling).forEach { signal ->
      nitsFromSdrSignal(signal) shouldRound nitsFromSdrSignal(signal.toDouble())
    }
  }

  @Test
  fun `the per channel HLG transfers round their double definition once`() {
    grid(0f, 1f).forEach { scene ->
      hlgDisplayNitsFromScene(scene) shouldRound hlgDisplayNitsFromScene(scene.toDouble())
      sdrSignalFromHlgScene(scene) shouldRound sdrSignalFromHlgScene(scene.toDouble())
    }
    grid(0f, 1f).forEach { signal ->
      hlgDisplayNitsFromSignal(signal) shouldRound hlgDisplayNitsFromSignal(signal.toDouble())
    }
    grid(0f, HLG_NOMINAL_PEAK_NITS).forEach { nits ->
      hlgSceneFromDisplayNits(nits) shouldRound hlgSceneFromDisplayNits(nits.toDouble())
      hlgSignalFromDisplayNits(nits) shouldRound hlgSignalFromDisplayNits(nits.toDouble())
    }
    grid(0f, HdrTransfer.Hlg.sdrSignalCeiling).forEach { signal ->
      hlgSceneFromSdrSignal(signal) shouldRound hlgSceneFromSdrSignal(signal.toDouble())
    }
  }

  @Test
  fun `the PQ transfers round their double definition once`() {
    grid(0f, PQ_PEAK_NITS).forEach { nits -> pqSignalFromNits(nits) shouldRound pqSignalFromNits(nits.toDouble()) }
    grid(0f, 1f).forEach { signal -> nitsFromPqSignal(signal) shouldRound nitsFromPqSignal(signal.toDouble()) }
  }

  @Test
  fun `the HLG signal transfers round their double definition once`() {
    grid(0f, 1f).forEach { value ->
      hlgSignalFromScene(value) shouldRound hlgSignalFromScene(value.toDouble())
      sceneFromHlgSignal(value) shouldRound sceneFromHlgSignal(value.toDouble())
    }
  }

  @Test
  fun `the luminance driven HLG transfers round their double definition once`() {
    colors(HLG_NOMINAL_PEAK_NITS).forEach { nits ->
      val wide = DoubleArray(3) { nits[it].toDouble() }
      hlgSceneFromNits(nits) shouldRound hlgSceneFromNits(wide)
      hlgSignalFromNits(nits) shouldRound hlgSignalFromNits(wide)
    }
    colors(1f).forEach { scene ->
      hlgNitsFromScene(scene) shouldRound hlgNitsFromScene(DoubleArray(3) { scene[it].toDouble() })
      hlgNitsFromSignal(scene) shouldRound hlgNitsFromSignal(DoubleArray(3) { scene[it].toDouble() })
    }
  }

  @Test
  fun `the SDR signal ceiling rounds its double once`() {
    HdrTransfer.entries.forEach { transfer ->
      transfer.sdrSignalCeiling shouldRound transfer.sdrSignalCeilingDouble
      nitsFromSdrSignal(transfer.sdrSignalCeilingDouble) shouldBeNear transfer.peakNits.toDouble()
    }
  }

  @Test
  fun `a double transfer decodes back to what it encoded`() {
    // Mid-range figures, where a transfer and a wrong reading of it part company.
    listOf(0.5, 44.0, 100.0, 1_000.0, 4_000.0).forEach { nits ->
      nitsFromPqSignal(pqSignalFromNits(nits)) shouldBeNear nits
      nitsFromSdrSignal(sdrSignalFromNits(nits)) shouldBeNear nits
    }
    listOf(0.01, 1.0 / 12.0, 0.3, 0.7).forEach { scene ->
      sceneFromHlgSignal(hlgSignalFromScene(scene)) shouldBeNear scene
      hlgSceneFromSdrSignal(sdrSignalFromHlgScene(scene)) shouldBeNear scene
    }
    listOf(0.1, 0.5, 0.75).forEach { signal ->
      hlgSignalFromDisplayNits(hlgDisplayNitsFromSignal(signal)) shouldBeNear signal
    }
    val color = doubleArrayOf(180.0, 420.0, 35.0)
    val back = hlgNitsFromScene(hlgSceneFromNits(color))
    repeat(3) { back[it] shouldBeNear color[it] }
    val decoded = hlgNitsFromSignal(hlgSignalFromNits(color))
    repeat(3) { decoded[it] shouldBeNear color[it] }
  }

  @Test
  fun `the per channel HLG decode runs the inverse transfer and then the gamma on each channel alone`() {
    // Mid-range signals, either side of the knee at a half.
    listOf(0.3, 0.75).forEach { signal ->
      val scene = sceneFromHlgSignal(signal)
      hlgDisplayNitsFromSignal(signal) shouldBeNear scene.pow(HLG_SYSTEM_GAMMA) * HLG_NOMINAL_PEAK_NITS
      hlgSignalFromDisplayNits(hlgDisplayNitsFromScene(scene)) shouldBeNear signal
    }
  }

  @Test
  fun `the HLG signal reads as the SDR signal of its scene light and back`() {
    // Mid-range signals, either side of the knee at a half.
    listOf(0.3, 0.45, 0.75).forEach { signal ->
      sdrSignalFromHlgSignal(signal) shouldBeNear sdrSignalFromNits(hlgDisplayNitsFromSignal(signal))
      hlgSignalFromSdrSignal(sdrSignalFromHlgSignal(signal)) shouldBeNear signal
    }
    // BT.2100's rounded constants decode a full signal a hair past one scene light.
    sdrSignalFromHlgSignal(1.0) shouldBe (HdrTransfer.Hlg.sdrSignalCeilingDouble plusOrMinus FULL_SIGNAL_SLACK)
    hlgSignalFromSdrSignal(HdrTransfer.Hlg.sdrSignalCeilingDouble * 2) shouldBe 1.0
  }

  // A linear sweep plus a logarithmic one, so the dark end, where the curves bend hardest, gets as many points as the
  // bright end.
  private fun grid(
    low: Float,
    high: Float,
  ): List<Float> {
    val linear = (0..GRID_STEPS).map { low + (high - low) * it / GRID_STEPS }
    val decades = high.toDouble() / LOG_FLOOR
    val logarithmic = (0..GRID_STEPS).map { (LOG_FLOOR * decades.pow(it.toDouble() / GRID_STEPS)).toFloat() }
    return (linear + logarithmic).map { it.stored() }
  }

  private fun colors(high: Float): List<FloatArray> {
    val axis = (0..COLOR_STEPS).map { (high * it / COLOR_STEPS).stored() }
    return axis.flatMap { red -> axis.flatMap { green -> axis.map { blue -> floatArrayOf(red, green, blue) } } }
  }

  // Kotlin/JS only rounds a Float to 32 bits when it lands in a FloatArray, so a figure that comes back unchanged from
  // one is a real Float.
  private fun Float.stored(): Float = floatArrayOf(this)[0]

  private infix fun Float.shouldRound(exact: Double) {
    withClue("$this is not a Float for $exact") { this shouldBe stored() }
    val step = Float.fromBits(abs(this).toBits() + 1).toDouble() - abs(this).toDouble()
    withClue("$this is more than half a Float step from $exact") {
      (abs(toDouble() - exact) <= step / 2).shouldBeTrue()
    }
  }

  private infix fun FloatArray.shouldRound(exact: DoubleArray) = repeat(3) { this[it] shouldRound exact[it] }

  private infix fun Double.shouldBeNear(expected: Double) {
    withClue("expected $expected but was $this") { (abs(this - expected) <= abs(expected) * 1e-9).shouldBeTrue() }
  }

  private companion object {
    const val GRID_STEPS = 4_000
    const val COLOR_STEPS = 12
    const val LOG_FLOOR = 1e-4f
    const val FULL_SIGNAL_SLACK = 1e-6
  }
}
