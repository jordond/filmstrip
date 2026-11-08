package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the browser engine lands a loop wrap when the loop range changes under it, and what it reads while the wrap's
 * start is still on its way to the audio graph.
 *
 * A wrap's seek lands on the engine's dispatcher after the lap is reported, so a listener that moves the range when it
 * hears the lap always moves it while the wrap is in flight.
 */
class WebLoopWrapTest {
  // The new range sits in the middle of the old one, so its first slot is neither end of the old range. The wrap onto
  // the old range moves on to it, reports that as its landing, and counts one lap rather than two.
  @Test
  fun `a wrap in flight when the loop range changes lands on the new range`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      val engine = subject.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val oldWrap = loopWrapOf(OLD_RANGE, duration, FIXTURE_FRAME_RATE) ?: fail("$OLD_RANGE has nothing to loop.")
        val newWrap = loopWrapOf(NEW_RANGE, duration, FIXTURE_FRAME_RATE) ?: fail("$NEW_RANGE has nothing to loop.")
        withClue("$NEW_RANGE is not strictly inside $OLD_RANGE.") {
          (newWrap.firstSlot > oldWrap.firstSlot && newWrap.lastSlot < oldWrap.lastSlot).shouldBeTrue()
        }
        val newFirst = outputSlotTime(newWrap.firstSlot, FIXTURE_FRAME_RATE)

        engine.setLoopRange(OLD_RANGE)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(APPROACH, SeekAccuracy.Exact)
        awaitContract("the seek to $APPROACH to complete") { recorder.seekCompletions.size > seeks }

        val mover = RangeMover { engine.setLoopRange(NEW_RANGE) }
        val registration = engine.addListener(mover)
        val events = recorder.events.size
        val positions = recorder.playheads.size
        engine.play()
        awaitContract("the wrap onto $NEW_RANGE to land and wrap again") {
          recorder.events.drop(events).count { it is PlaybackEvent.RangeLooped } >= 2
        }
        engine.pause()
        registration.cancel()
        awaitStep("the engine to go quiet") { engine.awaitQuiet() }

        val since = recorder.events.drop(events)
        val laps = since.filterIsInstance<PlaybackEvent.RangeLooped>()
        laps.first().range shouldBe OLD_RANGE
        laps.drop(1).forEach { it.range shouldBe NEW_RANGE }
        since.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()

        val firstLap = since.indexOfFirst { it is PlaybackEvent.RangeLooped }
        since
          .drop(firstLap)
          .filterIsInstance<PlaybackEvent.SeekCompleted>()
          .first()
          .position shouldBe newFirst
        since
          .drop(firstLap + 1)
          .takeWhile { it !is PlaybackEvent.SeekCompleted }
          .filterIsInstance<PlaybackEvent.RangeLooped>()
          .shouldBeEmpty()
        since.filterIsInstance<PlaybackEvent.SeekCompleted>().drop(1).forEach { it.position shouldBe newFirst }

        val readings = recorder.playheads.drop(positions)
        val landed = readings.indexOf(newFirst)
        if (landed < 0) fail("The playhead never read $newFirst.")
        readings.drop(landed).forEach { it shouldBeGreaterThanOrEqualTo newFirst }
      }
    }

  // The wrap's start is held where it starts the audio graph, which goes on sounding past the wrap point until then, so
  // anything read off the graph in the meantime is past the landing.
  @Test
  fun `a wrap on the audio clock reads its landing until the graph starts there`() =
    contractTest { scope ->
      val held = CompletableDeferred<Unit>()
      var starts = 0
      val engine =
        BrowserPlayerEngine(
          parent = scope,
          planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
          config = PlayerConfig(),
          startAudio = { graph, from ->
            if (++starts == 2) held.await()
            graph?.start(from)
          },
        )
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(webFixtureComposition(audio = AudioSpec.Keep))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val wrap = loopWrapOf(OLD_RANGE, duration, FIXTURE_FRAME_RATE) ?: fail("$OLD_RANGE has nothing to loop.")
        val firstSlot = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)

        engine.setLoopRange(OLD_RANGE)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(APPROACH, SeekAccuracy.Exact)
        awaitContract("the seek to $APPROACH to complete") { recorder.seekCompletions.size > seeks }

        val events = recorder.events.size
        engine.play()
        awaitContract("the audio clock to run") { engine.clockRunning }
        awaitContract("the wrap's start to reach the audio graph") { starts == 2 }
        val lap = (events until recorder.events.size).first { recorder.events[it] is PlaybackEvent.RangeLooped }
        awaitContract("the wrap's seek to complete") {
          (lap + 1 until recorder.events.size).any { recorder.events[it] is PlaybackEvent.SeekCompleted }
        }
        val landed = (lap + 1 until recorder.events.size).first { recorder.events[it] is PlaybackEvent.SeekCompleted }
        val landedAt = recorder.playheadsAtEvents[landed]
        awaitContract("the playhead to be read $HELD_READINGS times past the landing") {
          recorder.playheads.size >= landedAt + HELD_READINGS
        }
        val released = recorder.playheads.size
        held.complete(Unit)
        engine.pause()
        awaitStep("the engine to go quiet") { engine.awaitQuiet() }

        recorder.playheads.subList(recorder.playheadsAtEvents[lap], released).forEach { it shouldBe firstSlot }
      }
    }

  /**
   * Moves the loop range once, from inside the report of the first lap.
   */
  private class RangeMover(
    private val move: () -> Unit,
  ) : EngineListener {
    private var moved = false

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) {
      if (event !is PlaybackEvent.RangeLooped || moved) return
      moved = true
      move()
    }

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) = Unit
  }

  private companion object {
    // Both ranges end a third of a slot past a slot and sit well clear of the fixture's last one. The new one is the
    // middle of the old one.
    val OLD_RANGE: TimeRange =
      TimeRange.of(600.milliseconds + FIXTURE_FRAME_STEP / 3, 1100.milliseconds + FIXTURE_FRAME_STEP / 3)
    val NEW_RANGE: TimeRange =
      TimeRange.of(800.milliseconds + FIXTURE_FRAME_STEP / 3, 950.milliseconds + FIXTURE_FRAME_STEP / 3)
    val APPROACH: Duration = 1000.milliseconds
    const val HELD_READINGS = 10
  }
}
