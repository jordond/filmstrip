package dev.jordond.filmstrip.compose.ui.interaction

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WheelZoomGestureTest {
  @Test
  fun `a negative delta past the threshold steps in once`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(-3f, 100f)

    recorder.zoomIns shouldBe listOf(100f)
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `a negative delta past two thresholds steps in twice`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(-6f, 100f)

    recorder.zoomIns shouldBe listOf(100f, 100f)
  }

  @Test
  fun `a positive delta past the threshold steps out`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(3f, 100f)

    recorder.zoomOuts shouldBe listOf(100f)
    recorder.zoomIns shouldBe emptyList()
  }

  @Test
  fun `a delta that never crosses the threshold steps nothing`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(1f, 100f)

    recorder.zoomIns shouldBe emptyList()
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `small deltas in the same direction accumulate to one step rather than several`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    // Three notches summing to exactly the threshold, the way a flick reports many small deltas
    // rather than one big one.
    gesture.accumulate(-1f, 100f)
    gesture.accumulate(-1f, 100f)
    gesture.accumulate(-1f, 100f)

    recorder.zoomIns shouldBe listOf(100f)
  }

  @Test
  fun `an infinite delta is ignored rather than looping forever`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(Float.POSITIVE_INFINITY, 100f)

    recorder.zoomIns shouldBe emptyList()
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `the accumulator resets between gestures`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    // -2 combined with itself crosses the threshold, but a reset between the two calls means
    // neither one does on its own.
    gesture.accumulate(-2f, 100f)
    gesture.reset()
    gesture.accumulate(-2f, 100f)

    recorder.zoomIns shouldBe emptyList()
  }

  private class Recorder {
    val zoomIns: MutableList<Float> = mutableListOf()
    val zoomOuts: MutableList<Float> = mutableListOf()

    fun gesture(): WheelZoomGesture =
      WheelZoomGesture(
        onZoomIn = { focalX -> zoomIns += focalX },
        onZoomOut = { focalX -> zoomOuts += focalX },
      )
  }
}
