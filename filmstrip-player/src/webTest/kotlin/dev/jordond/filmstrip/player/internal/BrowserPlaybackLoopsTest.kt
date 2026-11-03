package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The transport tick and the render pump the browser engine runs while it plays, driven off the page's own clock and
 * frame callback.
 */
class BrowserPlaybackLoopsTest {
  @Test
  fun `both loops run while playback does`() =
    contractTest { scope ->
      var ticks = 0
      var frames = 0
      val loops = BrowserPlaybackLoops(scope, { true }, { ticks++ }, { frames++ < Int.MAX_VALUE })

      loops.start()
      awaitContract("the tick and the pump to run more than once") { ticks > 2 && frames > 2 }
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

      awaitContract("the tick to carry on") { ticks > ticked }
      frames shouldBe LAST_FRAME
      loops.stop()
    }

  private companion object {
    const val LAST_FRAME = 3
  }
}
