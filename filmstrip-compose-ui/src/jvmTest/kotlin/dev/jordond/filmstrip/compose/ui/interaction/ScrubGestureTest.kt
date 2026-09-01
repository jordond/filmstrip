package dev.jordond.filmstrip.compose.ui.interaction

import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScrubGestureTest {
  @Test
  fun `a gesture starts once, seeks on the way, and ends once`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.press(100f)
    gesture.press(150f)
    gesture.drag(200f)
    gesture.drag(400f)
    gesture.release()
    gesture.release()

    recorder.starts shouldBe 1
    recorder.ends shouldBe 1
    recorder.seeks shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
  }

  @Test
  fun `a drag outside a gesture seeks nothing`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.drag(300f)

    recorder.starts shouldBe 0
    recorder.seeks shouldBe emptyList()
    recorder.ends shouldBe 0
  }

  @Test
  fun `a second gesture starts again`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.press(100f)
    gesture.release()
    gesture.press(500f)
    gesture.release()

    recorder.starts shouldBe 2
    recorder.ends shouldBe 2
    recorder.seeks shouldBe listOf(1.seconds, 5.seconds)
  }

  @Test
  fun `a scrolled timeline seeks where the finger is, not where the viewport starts`() {
    val recorder = Recorder()
    val gesture = recorder.gesture(scrollPx = 800f)

    gesture.press(100f)
    gesture.drag(250f)

    recorder.seeks shouldBe listOf(9.seconds, 10.seconds + 500.milliseconds)
  }

  @Test
  fun `an offset timeline seeks player time, not timeline time`() {
    val recorder = Recorder()
    val gesture = recorder.gesture(sourceOffset = 4.seconds)

    gesture.press(500f)
    gesture.drag(900f)

    recorder.seeks shouldBe listOf(1.seconds, 5.seconds)
  }

  @Test
  fun `a press left of the offset clamps to zero rather than going negative`() {
    val recorder = Recorder()
    val gesture = recorder.gesture(sourceOffset = 4.seconds)

    gesture.press(100f)

    recorder.seeks shouldBe listOf(Duration.ZERO)
  }

  private class Recorder {
    var starts: Int = 0
    var ends: Int = 0
    val seeks: MutableList<Duration> = mutableListOf()

    fun gesture(
      scrollPx: Float = 0f,
      sourceOffset: Duration = Duration.ZERO,
    ): ScrubGesture =
      ScrubGesture(
        scale = TimelineScale(30.seconds, PIXELS_PER_SECOND),
        scrollPx = { scrollPx },
        sourceOffset = { sourceOffset },
        onStart = { starts++ },
        onSeek = { seeks += it },
        onEnd = { ends++ },
      )
  }

  private companion object {
    const val PIXELS_PER_SECOND = 100f
  }
}
