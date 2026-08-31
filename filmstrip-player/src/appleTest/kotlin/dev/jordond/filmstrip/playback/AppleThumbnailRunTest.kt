package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.playback.contract.contractTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What a whole strip run gets back, over the path a host drives.
 *
 * The other Apple thumbnail suites ask the source for one frame at a time. This one goes through
 * the facade, so the run reaches the source the way a strip sends it and comes back through the
 * default batching, which walks the run one request at a time. That default is shared code and its
 * callbacks arrive here on AVFoundation's own queue rather than on the thread that asked, which is
 * the part no host test covers.
 *
 * The total it prints is what the strip's latency is judged on, for anyone measuring the path
 * again.
 */
class AppleThumbnailRunTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a run comes back whole and in the order it was asked for`() =
    contractTest {
      val filmstrip = Filmstrip { playerBackend() }
      val composition = appleFixtureComposition()

      val started = TimeSource.Monotonic.markNow()
      val received = filmstrip.frames(composition, RUN_POSITIONS, STRIP_HEIGHT).toList()
      println("a run of ${received.size} tiles filled in ${started.elapsedNow().inWholeMilliseconds}ms")

      try {
        received.size shouldBe RUN_POSITIONS.size

        val frames = received.map { it as? FrameResult.Success ?: fail(it) }
        frames.forEach { it.image.heightPx shouldBe STRIP_HEIGHT }

        // Tolerances are left at their defaults, so a frame lands on the nearest sync sample rather
        // than on the position asked for and two positions may share one. Ascending requests can
        // only come back ascending, and a run delivered out of order would not.
        val times = frames.map { it.presentationTime }
        assertTrue(
          times.zipWithNext().all { (earlier, later) -> earlier <= later },
          "the run came back out of order: $times",
        )
      } finally {
        received.forEach { (it as? FrameResult.Success)?.image?.close() }
      }
    }

  private fun fail(result: FrameResult): Nothing =
    kotlin.test.fail("a tile of the run failed: ${(result as FrameResult.Failure).error.message}")

  private companion object {
    const val STRIP_HEIGHT = 120

    // Eight tiles across the fixture, none of them on a boundary.
    val RUN_POSITIONS: List<Duration> =
      listOf(100, 300, 500, 700, 900, 1100, 1300, 1400).map { it.milliseconds }
  }
}
