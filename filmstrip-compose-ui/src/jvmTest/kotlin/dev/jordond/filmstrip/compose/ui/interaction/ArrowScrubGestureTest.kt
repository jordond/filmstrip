package dev.jordond.filmstrip.compose.ui.interaction

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ArrowScrubGestureTest {
  @Test
  fun `advancing opens one scrub, and repeats do not reopen it`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.end()
    gesture.end()

    recorder.starts shouldBe 1
    recorder.ends shouldBe 1
  }

  @Test
  fun `the target advances by delta per repeat rather than re-reading a stale position`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    // The reported position stays pinned at 10s throughout, the way a relaxed scrub's position
    // does while a seek is still in flight. A repeat re-reading it would seek 11s three times
    // instead of accumulating.
    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }

    recorder.seeks shouldBe listOf(11.seconds, 12.seconds, 13.seconds)
  }

  @Test
  fun `a new burst reseeds the target from a fresh position`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.advance(1.seconds, currentPosition = { 10.seconds }) { it }
    gesture.end()
    gesture.advance(1.seconds, currentPosition = { 20.seconds }) { it }

    recorder.seeks shouldBe listOf(11.seconds, 21.seconds)
    recorder.starts shouldBe 2
    recorder.ends shouldBe 1
  }

  @Test
  fun `the clamp is applied to the accumulated target`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.advance(5.seconds, currentPosition = { 8.seconds }) { it.coerceAtMost(10.seconds) }

    recorder.seeks shouldBe listOf(10.seconds)
  }

  @Test
  fun `ending before any advance does nothing`() {
    val recorder = Recorder()
    val gesture = recorder.gesture()

    gesture.end()

    recorder.starts shouldBe 0
    recorder.ends shouldBe 0
  }

  private class Recorder {
    var starts: Int = 0
    var ends: Int = 0
    val seeks: MutableList<Duration> = mutableListOf()

    fun gesture(): ArrowScrubGesture =
      ArrowScrubGesture(
        onStart = { starts++ },
        onSeek = { seeks += it },
        onEnd = { ends++ },
      )
  }
}
