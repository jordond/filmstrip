package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.heldPosition
import dev.jordond.filmstrip.player.internal.seekLandingOf
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a composition change leaves of the transport and the playhead, run by [PlayerEngineContractTest] on every
 * backend.
 *
 * Every landing is read against [heldPosition] or [seekLandingOf], so a backend reading either differently fails here.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case starts from.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class RebuildContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: EditComposition,
  private val rate: Int,
) {
  /**
   * Plays from the start of the composition, rebuilds it, and checks each reading from the last one before the rebuild
   * on is at or past where [heldPosition] places the reading ahead of it, with no more progress than wall time allows.
   */
  fun keepsPlaying(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val duration = subject.engine.loadSeekable(recorder, fixture)

        val from = offGrid(duration, PLAY_FROM, rate, thirds = 0)
        subject.engine.seekAndWait(recorder, from)
        subject.engine.play()
        awaitContract("playback to move the playhead") { recorder.playheads.last() > from }

        val before = recorder.playheads
        val since = TimeSource.Monotonic.markNow()
        val eventsMark = recorder.events.size
        subject.engine.awaitComposition(fixture.cutInTwo(rate)).shouldBeInstanceOf<SetCompositionResult.Success>()
        recorder.lastState.playWhenReady shouldBe true
        awaitContract("the playhead to advance after the rebuild") {
          val after = recorder.playheads.drop(before.size)
          after.size >= 2 && after.last() > after.first()
        }

        recorder.lastState.playWhenReady shouldBe true
        subject.engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        subject.quiet()

        val elapsed = since.elapsedNow()
        recorder.events.drop(eventsMark).filterIsInstance<PlaybackEvent.Ended>() shouldBe emptyList()
        val readings = recorder.playheads.drop(before.size - 1)
        readings.zipWithNext { earlier, later ->
          later shouldBeGreaterThanOrEqualTo heldPosition(earlier, duration, rate, null, playing = true)
        }
        readings.last() shouldBeLessThanOrEqualTo readings.first() + elapsed + READING_SLACK
      }
    }

  /**
   * Rebuilds a paused composition at its middle, and checks the transport stays paused on where [heldPosition] lands.
   */
  fun staysPaused(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val duration = subject.engine.loadSeekable(recorder, fixture)

        val middle = offGrid(duration, HOLD_AT, rate, thirds = 0)
        subject.engine.seekAndWait(recorder, middle)
        subject.quiet()

        val mark = recorder.mark()
        val playheads = recorder.playheads.size
        val rebuilt = subject.engine.awaitComposition(fixture.cutInTwo(rate))
        rebuilt.shouldBeInstanceOf<SetCompositionResult.Success>().duration shouldBe duration
        awaitContract("the rebuild to finish") { recorder.lastState.status == PlaybackStatus.Ready }
        subject.quiet()

        recorder.statesSince(mark).forEach { it.playWhenReady shouldBe false }
        recorder.reportedSince(playheads) shouldBe heldPosition(middle, duration, rate, null, playing = false)
      }
    }

  /**
   * Seeks near the end of [fixture], swaps in a composition half as long while paused, and checks the playhead lands
   * where [heldPosition] places it in the shorter one, with or without an open loop range standing through the rebuild.
   *
   * A paused playhead lands on the last frame either way, since a range only wraps while playback runs.
   */
  fun pausedLandsOnLastFrame(loop: Boolean): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val duration = subject.engine.loadSeekable(recorder, fixture)

        val range = if (loop) TimeRange.from(offGrid(duration, LOOP_HELD_FROM, rate, thirds = 0)) else null
        range?.let(subject.engine::setLoopRange)
        val at = offGrid(duration, SHORTEN_FROM, rate, thirds = 0)
        subject.engine.seekAndWait(recorder, at)
        subject.quiet()

        val playheads = recorder.playheads.size
        val shorter = outputSlotTime(outputSlotOf(duration / 2, rate), rate)
        val rebuilt = subject.engine.awaitComposition(fixture.shortenedTo(shorter))
        val loaded = rebuilt.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the rebuild to finish") { recorder.lastState.status == PlaybackStatus.Ready }
        subject.quiet()

        recorder.lastState.playWhenReady shouldBe false
        recorder.reportedSince(playheads) shouldBe heldPosition(at, loaded.duration, rate, range, playing = false)
      }
    }

  /**
   * Plays near the end of [fixture] under an open loop range, swaps in a composition half as long, and checks playback
   * goes on from where [heldPosition] wraps it to, which is well short of the reading it left.
   */
  fun playingWrapsIntoLoopRange(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val duration = subject.engine.loadSeekable(recorder, fixture)

        val range = TimeRange.from(offGrid(duration, LOOP_HELD_FROM, rate, thirds = 0))
        subject.engine.setLoopRange(range)
        val at = offGrid(duration, SHORTEN_FROM, rate, thirds = 0)
        subject.engine.seekAndWait(recorder, at)
        subject.engine.play()
        awaitContract("playback to move the playhead") { recorder.playheads.last() > at }

        val before = recorder.playheads.size
        val shorter = outputSlotTime(outputSlotOf(duration / 2, rate), rate)
        val rebuilt = subject.engine.awaitComposition(fixture.shortenedTo(shorter))
        val loaded = rebuilt.shouldBeInstanceOf<SetCompositionResult.Success>()
        val wrapped = heldPosition(at, loaded.duration, rate, range, playing = true)
        recorder.lastState.playWhenReady shouldBe true
        awaitContract("the playhead to advance from the wrap") { recorder.playheads.last() > wrapped }

        recorder.lastState.playWhenReady shouldBe true
        subject.engine.pause()
        awaitContract("the play axis to settle after pausing") { !recorder.lastState.playWhenReady }
        subject.quiet()

        val readings = recorder.playheads.drop(before)
        val landing = readings.indexOfFirst { it < at }
        landing shouldBeGreaterThanOrEqualTo 0
        readings.drop(landing).forEach { it shouldBeGreaterThanOrEqualTo wrapped }
      }
    }

  /**
   * Seeks past the end of a standing loop range while paused, loads [next], and checks the playhead is still on the
   * slot the seek landed on, since a range only wraps while playback runs.
   */
  fun pausedStaysPastLoopRange(next: EditComposition): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        val duration = subject.engine.loadSeekable(recorder, fixture)

        val range =
          TimeRange.of(offGrid(duration, RANGE_FROM, rate, thirds = 0), offGrid(duration, RANGE_TO, rate, thirds = 0))
        subject.engine.setLoopRange(range)
        val past = offGrid(duration, PAST_RANGE_AT, rate, thirds = 0)
        subject.engine.seekAndWait(recorder, past)
        subject.quiet()
        val landed = seekLandingOf(past, duration, rate)

        val mark = recorder.mark()
        subject.engine.awaitComposition(next).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the edit to finish") { recorder.lastState.status == PlaybackStatus.Ready }
        subject.quiet()

        recorder.statesSince(mark).forEach { it.playWhenReady shouldBe false }
        recorder.playhead shouldBe landed
      }
    }

  private suspend fun EngineUnderTest.quiet() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private companion object {
    // Where across the composition each case puts the playhead first, and where a held loop range starts.
    const val PLAY_FROM = 0.1
    const val HOLD_AT = 0.5
    const val SHORTEN_FROM = 0.8
    const val LOOP_HELD_FROM = 0.2

    // A loop range the seek lands well past, all of it clear of the composition's ends.
    const val RANGE_FROM = 0.3
    const val RANGE_TO = 0.5
    const val PAST_RANGE_AT = 0.7

    // A playhead can be up to one reading ahead of the last one delivered when the wall clock starts counting.
    val READING_SLACK = 250.milliseconds
  }
}

/**
 * A time [thirds] thirds of a slot away from the output slot nearest [fraction] of [duration], at [rate].
 *
 * A third either side of a slot still rounds to it. Fails when that slot is the first or the last, since a seek near
 * either end lands there under any reading.
 */
internal fun offGrid(
  duration: Duration,
  fraction: Double,
  rate: Int,
  thirds: Int,
): Duration {
  val slot = outputSlotOf(duration * fraction, rate)
  val last = outputSlotOf(duration, rate) - 1
  check(slot in 1 until last) { "Slot $slot is not strictly inside $duration at $rate fps." }
  return outputSlotTime(slot, rate) + (1.seconds / rate / 3) * thirds
}
