package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickerTest {
  private var playhead = Duration.ZERO
  private val emitted = mutableListOf<Duration>()

  @Test
  fun `start emits a reading straight away and one more each interval`() =
    runTest {
      val ticker = ticker()

      ticker.start()
      runCurrent()
      emitted shouldBe listOf(Duration.ZERO)

      playhead = 17.milliseconds
      tick()
      playhead = 33.milliseconds
      tick()

      emitted shouldBe listOf(Duration.ZERO, 17.milliseconds, 33.milliseconds)
    }

  @Test
  fun `nothing is emitted part way through an interval`() =
    runTest {
      val ticker = ticker()
      ticker.start()
      runCurrent()

      advanceTimeBy(INTERVAL / 2)
      runCurrent()

      emitted.size shouldBe 1
    }

  @Test
  fun `starting a running ticker leaves one loop reading`() =
    runTest {
      val ticker = ticker()

      ticker.start()
      runCurrent()
      ticker.start()
      runCurrent()
      repeat(3) { tick() }

      emitted.size shouldBe 4
    }

  @Test
  fun `stop ends the readings and a later start emits straight away again`() =
    runTest {
      val ticker = ticker()
      ticker.start()
      runCurrent()

      ticker.stop()
      ticker.stop()
      repeat(3) { tick() }
      emitted.size shouldBe 1

      playhead = 500.milliseconds
      ticker.start()
      runCurrent()
      emitted shouldBe listOf(Duration.ZERO, 500.milliseconds)
      ticker.stop()
    }

  @Test
  fun `a ticker never started reads nothing`() =
    runTest {
      ticker()

      repeat(3) { tick() }

      emitted.shouldBeEmpty()
    }

  private fun TestScope.ticker(): PositionTicker =
    PositionTicker(backgroundScope, INTERVAL, read = { playhead }, emit = { emitted += it })

  private fun TestScope.tick() {
    advanceTimeBy(INTERVAL)
    runCurrent()
  }

  private companion object {
    val INTERVAL = 16.milliseconds
  }
}
