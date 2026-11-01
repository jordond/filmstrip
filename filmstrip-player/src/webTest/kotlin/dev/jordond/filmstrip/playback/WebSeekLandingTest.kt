package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitStep
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.playback.internal.loopWrapOf
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the browser engine lands a seek that waited on the preview.
 *
 * A relaxed seek reads the sync sample under the preview's lock, so a queue of readbacks taken out first holds its
 * landing open for as long as they take to decode. Each test checks the hold outlasted what it has to, and fails
 * rather than passes when it did not.
 */
class WebSeekLandingTest {
  // The landing is held until the rebuild released the old preview. It then has nothing to write, so the playhead
  // stays on the slot the rebuild started at and the only completion is the one the chase gave when it dropped it.
  @Test
  fun `a seek landing that outlives a rebuild writes nothing`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val start = outputSlotTime(outputSlotOf(REBUILD_START, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

        val hold = PreviewHold(engine, LONG_HOLD)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        engine
          .awaitComposition(webFixtureTrimmed(SHORTENED_LENGTH), startAt = REBUILD_START)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the held readbacks to drain") { hold.drained }
        engine.awaitQuietDone()

        // Only a readback that took the lock after the release comes back without a frame, and the landing queued
        // behind it.
        hold.last.shouldBeInstanceOf<ReadbackResult.Failure>()
        engine.clockPosition shouldBe start
        recorder.seekCompletions.drop(seeks).map { it.position } shouldBe listOf(HELD_TARGET)
      }
    }

  // The playhead sits past the wrap point with playback running, and the relaxed seek back into the range is held.
  // Nothing wraps until it lands, and the lap after it lands on the range's first slot.
  @Test
  fun `no wrap is taken while a seek that is not a wrap is in flight`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val wrap = loopWrapOf(RANGE, duration, FIXTURE_FRAME_RATE) ?: fail("$RANGE has nothing to loop.")
        val past = outputSlotTime(wrap.lastSlot + 2, FIXTURE_FRAME_RATE)
        val landing = outputSlotTime(outputSlotOf(HELD_SYNC_SAMPLE, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        val first = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        assertTrue(landing > first && landing < wrap.at, "The held seek does not land inside $RANGE.")

        engine.setLoopRange(RANGE)
        val parked = recorder.seekCompletions.size
        engine.seekTo(past, SeekAccuracy.Exact)
        awaitContract("the seek to $past to complete") { recorder.seekCompletions.size > parked }

        val log = EventLog()
        val registration = engine.addListener(log)
        val hold = PreviewHold(engine, LONG_HOLD)
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        engine.play()
        awaitContract("the wrap after the held seek to land") {
          log.entries
            .dropWhile { it !is PlaybackEvent.SeekCompleted }
            .drop(1)
            .dropWhile { it !is PlaybackEvent.RangeLooped }
            .any { it is PlaybackEvent.SeekCompleted }
        }
        engine.pause()
        engine.awaitQuietDone()
        registration.cancel()

        val entries = log.entries
        val landed = entries.indexOfFirst { it is PlaybackEvent.SeekCompleted }
        if (landed < 0) fail("The held seek never completed.")
        val held = entries.take(landed)
        held.filterIsInstance<PlaybackEvent.RangeLooped>().shouldBeEmpty()
        held.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        // The landing reports its own reading just before its completion.
        val readings = held.filterIsInstance<Duration>()
        readings.lastOrNull() shouldBe landing
        val waiting = readings.dropLast(1)
        assertTrue(waiting.size >= HELD_READINGS, "The hold ended after ${waiting.size} playhead readings.")
        waiting.forEach { it shouldBeGreaterThanOrEqualTo wrap.at }
        (entries[landed] as PlaybackEvent.SeekCompleted).position shouldBe landing
        hold.drained shouldBe true

        val after = entries.drop(landed + 1).filterIsInstance<PlaybackEvent>()
        after.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        after.first().shouldBeInstanceOf<PlaybackEvent.RangeLooped>().range shouldBe RANGE
        after
          .filterIsInstance<PlaybackEvent.SeekCompleted>()
          .first()
          .position shouldBe first
      }
    }

  // The readbacks are still queued when the fence starts, and the landing has to wait out every one of them. Once the
  // fence returns, the hold has drained and the landing has written the playhead and reported once.
  @Test
  fun `the fence waits out a landing held behind readbacks`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val landing = outputSlotTime(outputSlotOf(HELD_SYNC_SAMPLE, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

        val hold = PreviewHold(engine, FENCE_HOLD)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        hold.drained shouldBe false
        engine.awaitQuietDone()

        hold.drained shouldBe true
        recorder.seekCompletions.drop(seeks).map { it.position } shouldBe listOf(landing)
        engine.clockPosition shouldBe landing
      }
    }

  // The second seek waits in the chase behind the held one and goes out only once that lands, which is after the fence
  // has started. The second hold is taken before anything lands, so its readbacks queue for the preview's lock behind
  // the first landing and ahead of the second. The fence still waits for the second landing.
  @Test
  fun `the fence waits out a landing chained while it waits`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val held = outputSlotTime(outputSlotOf(HELD_SYNC_SAMPLE, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        val chained = outputSlotTime(outputSlotOf(FIXTURE_SYNC_INTERVAL, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

        val ahead = PreviewHold(engine, FENCE_HOLD)
        val seeks = recorder.seekCompletions.size
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        engine.seekTo(CHAINED_TARGET, SeekAccuracy.Nearest)
        val behind = PreviewHold(engine, FENCE_HOLD)
        behind.drained shouldBe false
        engine.awaitQuietDone()

        ahead.drained shouldBe true
        behind.drained shouldBe true
        recorder.seekCompletions.drop(seeks).map { it.position } shouldBe listOf(held, chained)
        engine.clockPosition shouldBe chained
      }
    }

  // Play and pause back to back leave the transport start queued behind the pause. Once the fence has waited it out,
  // the clock is still where it was before play, and stays there.
  @Test
  fun `a pause straight after play leaves the clock stopped`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val before = engine.clockPosition
        val playheads = recorder.playheads.size

        engine.play()
        engine.pause()
        engine.awaitQuietDone()

        engine.clockRunning shouldBe false
        engine.clockPosition shouldBe before
        recorder.playheads.drop(playheads).forEach { it shouldBe before }
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  private suspend fun BrowserPlayerEngine.awaitQuietDone() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  /**
   * A queue of readbacks that keeps the preview's lock busy, holding back every landing that queues for the lock after
   * it.
   *
   * The positions alternate between the last frames of two groups of pictures, so every readback decodes a whole group
   * from its sync sample rather than reading a frame the one before it left behind.
   *
   * @param readbacks How many readbacks to queue.
   */
  private class PreviewHold(
    engine: BrowserPlayerEngine,
    readbacks: Int,
  ) {
    private val results = arrayOfNulls<ReadbackResult>(readbacks)

    init {
      repeat(readbacks) { index ->
        engine.readback.requestFrame(HOLD_POSITIONS[index % HOLD_POSITIONS.size]) { results[index] = it }
      }
    }

    val drained: Boolean get() = results.all { it != null }

    val last: ReadbackResult? get() = results.last()
  }

  /**
   * Every event and playhead reading an engine delivers, in one list and in the order they arrived.
   */
  private class EventLog : EngineListener {
    var entries: List<Any> = emptyList()
      private set

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) {
      entries = entries + event
    }

    override fun onPosition(position: Duration) {
      entries = entries + position
    }

    override fun onPreviewInfo(info: PreviewInfo) = Unit
  }

  private companion object {
    // Long enough to outlast a rebuild or several playhead readings on the wall clock, even on a fast machine.
    const val LONG_HOLD = 256

    // Only has to be queued ahead of the landing when the fence starts.
    const val FENCE_HOLD = 16
    const val HELD_READINGS = 3

    val HELD_TARGET: Duration = 1050.milliseconds

    // Inside the second group of pictures, so it lands on that group's sync sample rather than the held one's.
    val CHAINED_TARGET: Duration = 700.milliseconds
    val HELD_SYNC_SAMPLE: Duration = FIXTURE_SYNC_INTERVAL * 2
    val REBUILD_START: Duration = 300.milliseconds
    val SHORTENED_LENGTH: Duration = 1400.milliseconds
    val HOLD_POSITIONS: List<Duration> =
      listOf(CLIP_LENGTH - FIXTURE_FRAME_STEP, FIXTURE_SYNC_INTERVAL - FIXTURE_FRAME_STEP)

    // Ends a third of a slot past a slot, well clear of the fixture's last one.
    val RANGE: TimeRange =
      TimeRange.of(600.milliseconds + FIXTURE_FRAME_STEP / 3, 1100.milliseconds + FIXTURE_FRAME_STEP / 3)
  }
}
