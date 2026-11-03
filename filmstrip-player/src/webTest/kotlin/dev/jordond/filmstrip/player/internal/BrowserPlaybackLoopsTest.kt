package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The transport tick and the render pump the browser engine runs while it plays, driven off the page's own clock and
 * frame callback.
 */
class BrowserPlaybackLoopsTest {
  @Test
  fun `both loops run for as long as playback does`() =
    contractTest { scope ->
      var playing = true
      var ticks = 0
      var frames = 0
      val loops = BrowserPlaybackLoops(scope, { playing }, { ticks++ }, { frames++ < Int.MAX_VALUE })

      loops.start()
      awaitContract("the tick and the pump to run more than once") { ticks > 2 && frames > 2 }
      playing = false
      val stoppedAt = settled { ticks to frames }

      delay(SETTLE)
      (ticks to frames) shouldBe stoppedAt
      loops.stop()
    }

  // The pump stops once there is nothing left to draw ahead of, and the tick keeps the transport moving.
  @Test
  fun `the pump stops when a frame has nothing to draw and the tick carries on`() =
    contractTest { scope ->
      var ticks = 0
      var frames = 0
      val loops = BrowserPlaybackLoops(scope, { true }, { ticks++ }, { ++frames < LAST_FRAME })

      loops.start()
      awaitContract("the pump to reach its last frame") { frames == LAST_FRAME }
      val ticked = ticks

      delay(SETTLE)
      frames shouldBe LAST_FRAME
      (ticks > ticked) shouldBe true
      loops.stop()
    }

  @Test
  fun `stopping ends both loops while playback still holds`() =
    contractTest { scope ->
      var ticks = 0
      var frames = 0
      val loops = BrowserPlaybackLoops(scope, { true }, { ticks++ }, { frames++ < Int.MAX_VALUE })

      loops.start()
      awaitContract("both loops to run") { ticks > 0 && frames > 0 }
      loops.stop()
      val stoppedAt = ticks to frames

      delay(SETTLE)
      (ticks to frames) shouldBe stoppedAt
    }

  // A preview released mid-decode must lose the decode as well as the callback that started it.
  @Test
  fun `stopping cancels a frame the pump has in flight`() =
    contractTest { scope ->
      val entered = CompletableDeferred<Unit>()
      var finished = false
      val loops =
        BrowserPlaybackLoops(scope, { true }, {}) {
          entered.complete(Unit)
          delay(LONG_DECODE)
          finished = true
          true
        }

      loops.start()
      entered.await()
      loops.stop()

      delay(LONG_DECODE + SETTLE)
      finished shouldBe false
    }

  /**
   * What [read] says once a loop has had the time to finish the step it was on when playback stopped.
   */
  private suspend fun <T> settled(read: () -> T): T {
    delay(SETTLE)
    return read()
  }

  private companion object {
    // Several transport ticks and at least a couple of painted frames, so a loop that kept running shows.
    val SETTLE = 150.milliseconds
    val LONG_DECODE = 200.milliseconds
    const val LAST_FRAME = 3
  }
}
