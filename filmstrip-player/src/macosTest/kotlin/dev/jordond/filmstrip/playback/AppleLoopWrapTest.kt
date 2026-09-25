package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.loopWrapOf
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Where the AVFoundation engine wraps a loop range that ends inside an output slot, well before the composition does.
 *
 * The shared contract suite covers ranges the composition ends inside. These cover a range whose end is off the grid,
 * where wrapping at the raw end either plays part of a slot the range does not carry or cuts its last slot short.
 */
class AppleLoopWrapTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a loop range ending just past a slot wraps before it`(): TestResult = wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_DOWN)

  @Test
  fun `a loop range ending just short of a slot wraps before it`(): TestResult =
    wrapsOntoItsFirstSlot(LOOP_END_ROUNDS_UP)

  /**
   * Loops a range ending at [end] and checks every wrap lands on the slot its start lands on, and that no reading
   * reaches the wrap [loopWrapOf] places.
   *
   * The start sits a third of a slot past its slot, so a playhead reporting the raw start is caught at every drop.
   */
  private fun wrapsOntoItsFirstSlot(end: Duration): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val range = TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP / 3, end)
        val wrap = loopWrapOf(range, duration, FIXTURE_FRAME_RATE) ?: fail("The loop range has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        wrap.reachesEnd shouldBe false
        wrap.lastSlot shouldBe LOOP_END_SLOT - 1

        val approach = outputSlotTime(wrap.lastSlot - APPROACH_SLOTS, FIXTURE_FRAME_RATE)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(approach, SeekAccuracy.Exact)
        awaitContract("the seek into the loop range to land") { recorder.seekCompletions.size > seeks }
        engine.setLoopRange(range)

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        engine.play()
        awaitContract("playback to wrap twice") {
          val looped = recorder.events.drop(eventsMark).count { it is PlaybackEvent.RangeLooped }
          looped >= 2 && dropIndices(recorder.playheads.drop(positionsMark)).size >= 2
        }
        engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        settle()

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }
        positions.forEach { it shouldBeLessThan wrap.at }
      }
    }

  private companion object {
    // Two slots whose times fall between two whole milliseconds, the second well short of the composition's end.
    const val LOOP_FIRST_SLOT = 10L
    const val LOOP_END_SLOT = 28L

    // Two fifths of a slot either side of LOOP_END_SLOT, so one end rounds down onto it and one rounds up.
    val LOOP_END_ROUNDS_DOWN: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) + FIXTURE_FRAME_STEP * 2 / 5
    val LOOP_END_ROUNDS_UP: Duration = outputSlotTime(LOOP_END_SLOT, FIXTURE_FRAME_RATE) - FIXTURE_FRAME_STEP * 2 / 5

    // How far before the range's last slot playback starts, so the first lap is short.
    const val APPROACH_SLOTS = 3

    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }
  }
}
