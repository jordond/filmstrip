package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeMonotonicallyIncreasing
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a seek and a shuttle put on a real surface through media3.
 *
 * media3 accepts a seek well before it draws the frame, so the engine answers a seek from the frame and not from the
 * acceptance. A shuttle is paced by those answers, which is why they have to follow the picture.
 */
class AndroidShuttleMediaTest {
  /**
   * The surface already shows the frame a seek landed on when the seek completes.
   */
  @Test
  fun aSeekCompletesOnceItsFrameIsOnTheSurface() =
    onSurface { surface ->
      for (second in SEEK_SECONDS) {
        val before = surface.picture()
        surface.seekAndAwait(second.seconds)
        val atCompletion = surface.pictureAfter(before, COMPOSITOR_LAG)
        delay(SETTLE)
        val settled = surface.picture()

        withClue("the seek to ${second}s completed before the surface moved off the frame it was on") {
          atCompletion shouldNotBe before
        }
        withClue("the seek to ${second}s completed on a frame the surface did not stay on") {
          atCompletion shouldBe settled
        }
      }
    }

  /**
   * A seek to where the paused player already rests completes, though media3 draws nothing for it.
   */
  @Test
  fun aSeekToTheFrameShowingCompletesAfterAPause() =
    onSurface { surface ->
      repeat(RESTING_SEEKS) {
        surface.player.play()
        delay(PLAY_SPELL)
        surface.player.pause()
        surface.seekAndAwait(surface.player.currentPosition())
        surface.seekAndAwait(surface.player.currentPosition())
      }
    }

  /**
   * A shuttle lands every frame on the output grid, which is media3's limit: it has no relaxed seek to land on a
   * sync frame with.
   */
  @Test
  fun aShuttleLandsEveryFrameExactlyOnTheOutputGrid() =
    onSurface { surface ->
      val positions = CopyOnWriteArrayList<Duration>()
      val listener = surface.player.addPositionListener(TICK) { positions += it }
      surface.seekAndAwait(Duration.ZERO)
      positions.clear()

      surface.player.beginShuttle(FAST)
      awaitOrFail("the shuttle to reach the end") {
        surface.events.any { it is PlaybackEvent.ShuttleReachedEdge && it.edge == ShuttleEdge.End }
      }
      surface.player.endShuttle()
      listener.cancel()

      val shown = positions.distinct()
      shown shouldHaveAtLeastSize MIN_SHUTTLE_FRAMES
      shown.shouldBeMonotonicallyIncreasing()
      // A position listener hears whole ticks, so a slot is told apart from a time between two by its millisecond.
      val grid = (0..LAST_SLOT).map { outputSlotTime(it.toLong(), RATE).inWholeMilliseconds.milliseconds }
      withClue("a shuttle frame landed between two slots") {
        shown.filter { it !in grid } shouldBe emptyList()
      }
      shown.last() shouldBe grid.last()
    }

  private fun onSurface(body: suspend (ShuttleSurface) -> Unit) =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) { withShuttleSurface(body = body) }
    }

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val SETTLE: Duration = 500.milliseconds

    // The engine completes a seek when media3 hands the frame to the surface. A phone's compositor shows it by the
    // time the surface is read. An emulator's software compositor shows it some refreshes later, so there the surface
    // gets this long to move.
    val COMPOSITOR_LAG: Duration = if (runsOnEmulator) 250.milliseconds else Duration.ZERO
    val PLAY_SPELL: Duration = 150.milliseconds
    val TICK: Duration = 1.milliseconds
    const val RATE = LONG_CLIP_RATE
    val SEEK_SECONDS = listOf(3, 9, 1, 6, 11, 4, 8, 2)

    const val FAST = 7f
    const val RESTING_SEEKS = 12
    const val MIN_SHUTTLE_FRAMES = 5
    val LAST_SLOT: Int = (LONG_CLIP_LENGTH.inWholeSeconds * LONG_CLIP_RATE).toInt() - 1
  }
}
