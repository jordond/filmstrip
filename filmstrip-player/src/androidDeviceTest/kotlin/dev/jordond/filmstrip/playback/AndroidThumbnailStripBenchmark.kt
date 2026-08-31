package dev.jordond.filmstrip.playback

import android.util.Log
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.playback.AndroidThumbnailStripBenchmark.Companion.TAG
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long a strip of eight tiles takes to fill, over the path the sample app drives.
 *
 * It reports to logcat under [TAG] so a host can read the arrival of each tile and the total, which
 * is what the strip's own latency is judged on. The ceiling it holds the run to is far enough above
 * what a slow emulator takes that what fails here is a regression that changed the order of the
 * cost, never a few milliseconds of drift.
 */
class AndroidThumbnailStripBenchmark {
  @Test
  fun eightTilesFill() =
    runTest(timeout = BUDGET) {
      val filmstrip = Filmstrip(contractContext()) { playerBackend() }
      val composition = androidFixtureComposition(listOf(Brightness(DIM)))

      // Warms the fixture's probe and the process's codec lookup, so the run below times the strip
      // rather than the first decode anything on this device ever did.
      filmstrip.frames(composition, listOf(WARMUP), STRIP_HEIGHT).collectIndexed { _, result ->
        (result as? FrameResult.Success)?.image?.close()
      }

      val started = TimeSource.Monotonic.markNow()
      var arrived = 0
      filmstrip.frames(composition, STRIP_POSITIONS, STRIP_HEIGHT).collectIndexed { index, result ->
        Log.i(TAG, "tile $index at ${started.elapsedNow().inWholeMilliseconds}ms ${result.outcome()}")
        (result as? FrameResult.Success)?.image?.close()
        arrived++
      }
      val elapsed = started.elapsedNow()
      Log.i(TAG, "total ${elapsed.inWholeMilliseconds}ms for $arrived tiles")

      arrived shouldBe STRIP_POSITIONS.size

      val ceiling = PER_TILE_CEILING * STRIP_POSITIONS.size
      assertTrue(
        elapsed <= ceiling,
        "The strip took $elapsed to fill ${STRIP_POSITIONS.size} tiles, past the $ceiling it is allowed.",
      )
    }

  private fun FrameResult.outcome(): String =
    when (this) {
      is FrameResult.Success -> "ok"
      is FrameResult.Failure -> "failed: ${error.message}"
    }

  private companion object {
    const val TAG = "FilmstripStripBench"
    const val DIM = 0.4f
    const val STRIP_HEIGHT = 120

    val BUDGET: Duration = 120.seconds
    val WARMUP: Duration = 50.milliseconds

    // Around five times what an emulator measures per tile and sixty times what a phone does, which
    // leaves the timings above to report the drift and this to catch a strip that went back to
    // decoding the clip once per tile.
    val PER_TILE_CEILING: Duration = 3.seconds

    // Eight tiles across the fixture, none of them on a boundary, each on its 30fps grid.
    val STRIP_POSITIONS: List<Duration> =
      listOf(100, 300, 500, 700, 900, 1100, 1300, 1400).map { it.milliseconds }
  }
}
