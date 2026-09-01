package dev.jordond.filmstrip.compose.ui.interaction

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ZoomGestureTest {
  @Test
  fun `a factor that crosses 2 steps in once`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(2f, 100f)

    recorder.zoomIns shouldBe listOf(100f)
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `a factor that crosses 4 steps in twice`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(4f, 100f)

    recorder.zoomIns shouldBe listOf(100f, 100f)
  }

  @Test
  fun `a factor that crosses one half steps out`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(0.5f, 100f)

    recorder.zoomOuts shouldBe listOf(100f)
    recorder.zoomIns shouldBe emptyList()
  }

  @Test
  fun `a factor that never crosses a doubling steps nothing`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(1.3f, 100f)

    recorder.zoomIns shouldBe emptyList()
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `a zero factor is ignored rather than pinning the accumulator`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.accumulate(0f, 100f)

    recorder.zoomIns shouldBe emptyList()
    recorder.zoomOuts shouldBe emptyList()
  }

  @Test
  fun `an infinite factor is ignored rather than looping forever`() {
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

    // 1.8 combined with itself crosses 2.0, but a reset between the two calls means neither one
    // does on its own.
    gesture.accumulate(1.8f, 100f)
    gesture.reset()
    gesture.accumulate(1.8f, 100f)

    recorder.zoomIns shouldBe emptyList()
  }

  private class Recorder {
    val zoomIns: MutableList<Float> = mutableListOf()
    val zoomOuts: MutableList<Float> = mutableListOf()

    fun gesture(): ZoomGesture =
      ZoomGesture(
        onZoomIn = { focalX -> zoomIns += focalX },
        onZoomOut = { focalX -> zoomOuts += focalX },
      )
  }
}
