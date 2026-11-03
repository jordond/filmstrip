package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The page globals the browser engine binds, called the way the engine calls them on both js and wasm.
 */
class BrowserGlobalsTest {
  @Test
  fun `a frame callback runs once the page paints`() =
    contractTest {
      var painted = 0

      requestAnimationFrame { painted++ }
      awaitContract("the page to paint") { painted > 0 }

      painted shouldBe 1
    }

  @Test
  fun `a cancelled frame callback never runs`() =
    contractTest {
      var cancelled = 0
      var later = 0

      cancelAnimationFrame(requestAnimationFrame { cancelled++ })
      requestAnimationFrame { later++ }
      awaitContract("the page to paint") { later > 0 }

      cancelled shouldBe 0
    }
}
