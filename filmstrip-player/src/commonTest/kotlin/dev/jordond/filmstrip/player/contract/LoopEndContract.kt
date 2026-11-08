package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.test.fail
import kotlin.time.Duration

/**
 * What a loop range the composition ends inside does at that end, run by [PlayerEngineContractTest] on every backend.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case plays.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class LoopEndContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: EditComposition,
  private val rate: Int,
) {
  /**
   * Plays into the end of a composition that ends inside the loop range [shape] builds, and checks it wraps there
   * twice.
   *
   * [shape] gets the range's start and the composition's duration. The start sits well inside the composition,
   * [thirds] thirds of a slot off the slot it rounds to, so a wrap that lands on the raw start rather than on that slot
   * is caught. Playback starts close to the end, so the first pass is short. The second wrap is what shows playback
   * ran the whole range again.
   */
  fun wrapsAtTheCompositionEnd(
    thirds: Int,
    shape: (start: Duration, duration: Duration) -> TimeRange,
  ): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")

        val range = shape(offGrid(duration, LOOP_FROM, rate, thirds), duration)
        val wrap = loopWrapOf(range, duration, rate) ?: fail("The loop range at ${range.start} has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, rate)
        subject.engine.setLoopRange(range)

        val approach = outputSlotTime(outputSlotOf(duration * APPROACH_AT, rate), rate)
        val seeks = recorder.seekCompletions.size
        subject.engine.seekTo(approach, SeekAccuracy.Exact)
        awaitContract("the seek to $approach to complete") { recorder.seekCompletions.size > seeks }

        val eventsMark = recorder.events.size
        val positionsMark = recorder.playheads.size
        subject.engine.play()
        awaitLoopWraps(recorder, eventsMark, positionsMark, count = 2)

        // A wrap landing between the events read and the playheads read below, or the landing playhead
        // arriving a moment before the RangeLooped event that explains it, is what makes this flaky.
        // Confirming playback is still running before pausing it, then waiting for that pause to settle,
        // means both reads see the same still record instead of racing a wrap in progress.
        recorder.lastState.playWhenReady shouldBe true
        recorder.lastState.status shouldNotBe PlaybackStatus.Ended
        subject.engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        awaitStep("the engine to go quiet") { subject.awaitQuiet() }

        val since = recorder.events.drop(eventsMark)
        since.filterIsInstance<PlaybackEvent.Ended>() shouldBe emptyList()
        val looped = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        looped.forEach { it.range shouldBe range }

        val positions = recorder.playheads.drop(positionsMark)
        val drops = dropIndices(positions)
        drops.map { index -> positions[index] }.forEach { it shouldBe firstSlot }
        drops.size shouldBe looped.size
        positions.drop(drops.first()).forEach { it shouldBeGreaterThanOrEqualTo firstSlot }

        // Once a wrap is decided the playhead reads its landing until the wrap's seek completes, whatever the platform
        // reads in between, so the first reading after each lap is the first slot and so is every one up to that seek.
        // From there the platform's own readings stay short of the wrap, up to the one straight before the next drop,
        // which can read the wrap itself as the clock reaches it.
        val events = recorder.events
        val marks = recorder.playheadsAtEvents
        val playheads = recorder.playheads
        val allDrops = dropIndices(playheads)
        val laps = (eventsMark until events.size).filter { events[it] is PlaybackEvent.RangeLooped }
        laps.forEachIndexed { number, lap ->
          playheads[marks[lap]] shouldBe firstSlot
          val landed =
            (lap + 1 until events.size).firstOrNull { events[it] is PlaybackEvent.SeekCompleted }
              ?: fail("The wrap's seek after lap ${number + 1} never completed.")
          playheads.subList(marks[lap], marks[landed]).forEach { it shouldBe firstSlot }
          val nextDrop = allDrops.firstOrNull { it >= marks[landed] }
          val playing = if (nextDrop == null) playheads.size else nextDrop - 1
          playheads.subList(marks[landed], playing.coerceAtLeast(marks[landed])).forEach { it shouldBeLessThan wrap.at }
        }
      }
    }

  /**
   * Waits for playback to have wrapped [count] times, with at least [count] drops recorded in the playhead since
   * [positionsMark].
   *
   * Fails as soon as the end is reported, rather than waiting out the budget for a wrap that is not coming.
   */
  private suspend fun awaitLoopWraps(
    recorder: ContractRecorder,
    eventsMark: Int,
    positionsMark: Int,
    count: Int,
  ) {
    awaitContract("playback to wrap $count times") {
      val since = recorder.events.drop(eventsMark)
      since.firstOrNull { it is PlaybackEvent.Ended }?.let { fail("Playback reported $it instead of wrapping.") }
      val looped = since.count { it is PlaybackEvent.RangeLooped }
      looped >= count &&
        dropIndices(recorder.playheads.drop(positionsMark)).size >= count &&
        recorder.playheads.size > lastLapMark(recorder, eventsMark)
    }
  }

  /**
   * How many playhead readings [recorder] held as the last [PlaybackEvent.RangeLooped] since [eventsMark] arrived.
   */
  private fun lastLapMark(
    recorder: ContractRecorder,
    eventsMark: Int,
  ): Int {
    val events = recorder.events
    val lap = (eventsMark until events.size).last { events[it] is PlaybackEvent.RangeLooped }
    return recorder.playheadsAtEvents[lap]
  }

  private companion object {
    // Where across the composition the loop range starts, and where playback into its end begins.
    const val LOOP_FROM = 0.7
    const val APPROACH_AT = 0.9

    /**
     * The index of every reading in [readings] that is lower than the one straight before it.
     *
     * That is what a wrap looks like in an ordered position record: a drop, not a plateau or a climb.
     */
    fun dropIndices(readings: List<Duration>): List<Int> =
      (1 until readings.size).filter { index -> readings[index] < readings[index - 1] }
  }
}
