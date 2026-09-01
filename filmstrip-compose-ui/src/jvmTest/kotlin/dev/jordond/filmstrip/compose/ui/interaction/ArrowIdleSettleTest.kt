package dev.jordond.filmstrip.compose.ui.interaction

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ArrowIdleSettleTest {
  @Test
  fun `pinging within the timeout does not settle`() =
    runTest {
      val recorder = Recorder()
      val settle = recorder.settle(this)

      settle.ping()
      advanceTimeBy(60.milliseconds)
      settle.ping()
      advanceTimeBy(60.milliseconds)
      settle.ping()
      advanceTimeBy(60.milliseconds)
      runCurrent()

      recorder.ends shouldBe 0
    }

  @Test
  fun `letting the countdown elapse settles the gesture exactly once`() =
    runTest {
      val recorder = Recorder()
      val settle = recorder.settle(this)

      settle.ping()
      advanceTimeBy(150.milliseconds)
      runCurrent()

      recorder.ends shouldBe 1
    }

  @Test
  fun `cancel drops a pending countdown`() =
    runTest {
      val recorder = Recorder()
      val settle = recorder.settle(this)

      settle.ping()
      settle.cancel()
      advanceTimeBy(500.milliseconds)
      runCurrent()

      recorder.ends shouldBe 0
    }

  @Test
  fun `a later ping restarts a countdown a cancelled one left stopped`() =
    runTest {
      val recorder = Recorder()
      val settle = recorder.settle(this)

      settle.ping()
      settle.cancel()
      settle.ping()
      advanceTimeBy(150.milliseconds)
      runCurrent()

      recorder.ends shouldBe 1
    }

  private class Recorder {
    var ends: Int = 0

    fun settle(scope: CoroutineScope): ArrowIdleSettle {
      val gesture = ArrowScrubGesture(onStart = {}, onSeek = {}, onEnd = { ends++ })
      gesture.advance(1.seconds, currentPosition = { 0.seconds }) { it }
      return ArrowIdleSettle(scope, gesture, timeout = TIMEOUT)
    }
  }

  private companion object {
    val TIMEOUT = 100.milliseconds
  }
}
