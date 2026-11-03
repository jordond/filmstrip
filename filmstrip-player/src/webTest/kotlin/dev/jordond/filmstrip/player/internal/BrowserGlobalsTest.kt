package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

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

  @Test
  fun `the page clock moves forward with real time`() =
    contractTest {
      val first = performance.now()
      delay(20.milliseconds)
      val second = performance.now()

      second shouldBeGreaterThan first
      (second - first) shouldBeGreaterThanOrEqualTo 10.0
    }
}
