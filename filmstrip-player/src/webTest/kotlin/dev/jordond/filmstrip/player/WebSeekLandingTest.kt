package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.loopWrapOf
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the browser engine lands a seek that waited on the preview.
 *
 * A relaxed seek reads the sync sample under the preview's lock before it lands. A test holds that read open until the
 * event it waits for has happened, or queues readbacks on the lock ahead of it so the landing waits for them to decode.
 */
class WebSeekLandingTest {
  // The landing is held until the rebuild released the old preview. It then has nothing to write, so the playhead
  // stays on the slot the rebuild started at and the only completion is the one the chase gave when it dropped it.
  @Test
  fun `a seek landing that outlives a rebuild writes nothing`() =
    contractTest { scope ->
      val hold = HeldSyncSample()
      val engine = WebEngineUnderTest(scope, readSyncSample = hold::read).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val start = outputSlotTime(outputSlotOf(REBUILD_START, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

        val release = hold.holdNext()
        val seeks = recorder.seekCompletions.size
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        awaitContract("the landing to be held") { hold.held }
        engine
          .awaitComposition(webFixtureTrimmed(SHORTENED_LENGTH), startAt = REBUILD_START)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        release.complete(Unit)
        engine.awaitQuietDone()

        // A released preview answers the position itself rather than a sync sample before it.
        hold.answers shouldBe listOf(HELD_TARGET)
        engine.clockPosition shouldBe start
        recorder.seekCompletions.drop(seeks).map { it.position } shouldBe listOf(HELD_TARGET)
      }
    }

  // The seek goes out from inside the edit's preview report, after the preview took the new parameters and before the
  // edit moves to its start, so its landing is still to come when the edit lands. It writes nothing, and the seek
  // completes where the edit put the playhead.
  @Test
  fun `a seek landing that outlives a parameter edit moved to a start writes nothing`() =
    contractTest { scope ->
      val engine = WebEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(webFixtureComposition(listOf(Brightness(DIM))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val start = outputSlotTime(outputSlotOf(SWAP_START, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        val loads = engine.platformLoads

        val seeker = SeekOnPreviewInfo(engine, HELD_TARGET)
        val registration = engine.addListener(seeker)
        seeker.armed = true
        val seeks = recorder.seekCompletions.size
        engine
          .awaitComposition(webFixtureComposition(listOf(Brightness(BRIGHT))), startAt = SWAP_START)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the seek to complete") { recorder.seekCompletions.size > seeks }
        engine.awaitQuietDone()
        registration.cancel()

        seeker.armed shouldBe false
        engine.platformLoads shouldBe loads
        engine.clockPosition shouldBe start
        recorder.seekCompletions.drop(seeks).map { it.position } shouldBe listOf(start)
        recorder.playhead shouldBe start
      }
    }

  // The playhead sits past the wrap point with playback running, and the relaxed seek back into the range is held.
  // Nothing wraps until it lands, the playhead reads its target meanwhile, and the lap after it lands on the range's
  // first slot.
  @Test
  fun `no wrap is taken while a seek that is not a wrap is in flight`() =
    contractTest { scope ->
      val hold = HeldSyncSample()
      val engine = WebEngineUnderTest(scope, readSyncSample = hold::read).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to loop within.")
        val wrap = loopWrapOf(RANGE, duration, FIXTURE_FRAME_RATE) ?: fail("$RANGE has nothing to loop.")
        val past = outputSlotTime(wrap.lastSlot + 2, FIXTURE_FRAME_RATE)
        val landing = outputSlotTime(outputSlotOf(HELD_SYNC_SAMPLE, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
        val first = outputSlotTime(wrap.firstSlot, FIXTURE_FRAME_RATE)
        withClue("The held seek does not land inside $RANGE.") { (landing > first && landing < wrap.at).shouldBeTrue() }

        engine.setLoopRange(RANGE)
        val parked = recorder.seekCompletions.size
        engine.seekTo(past, SeekAccuracy.Exact)
        awaitContract("the seek to $past to complete") { recorder.seekCompletions.size > parked }

        val log = EventLog()
        val registration = engine.addListener(log)
        val release = hold.holdNext()
        engine.seekTo(HELD_TARGET, SeekAccuracy.Nearest)
        engine.play()
        awaitContract("more than $HELD_READINGS playhead readings while the landing is held") {
          hold.held && log.entries.count { it is Duration } > HELD_READINGS
        }
        release.complete(Unit)
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
        withClue("The hold ended after ${waiting.size} playhead readings.") {
          (waiting.size >= HELD_READINGS).shouldBeTrue()
        }
        // The playhead holds the seek's target until it lands, whatever the clock reads past the wrap.
        waiting.forEach { it shouldBe HELD_TARGET }
        (entries[landed] as PlaybackEvent.SeekCompleted).position shouldBe landing

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
  }

  /**
   * Reads the sync sample a relaxed seek lands on and, once armed, holds the next read until the test lets it go.
   *
   * A held read takes the preview's lock only once it is let go, so whatever the test does meanwhile happens before
   * the landing.
   */
  private class HeldSyncSample {
    private var hold: CompletableDeferred<Unit>? = null

    var held: Boolean = false
      private set

    // What each held read answered once it was let go.
    var answers: List<Duration> = emptyList()
      private set

    fun holdNext(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { hold = it }

    suspend fun read(
      preview: BrowserPreview,
      position: Duration,
    ): Duration {
      val gate = hold ?: return preview.syncSampleAt(position)
      hold = null
      held = true
      gate.await()
      return preview.syncSampleAt(position).also { answers = answers + it }
    }
  }

  /**
   * Seeks [engine] exactly to [target] from inside the next preview report once [armed], and only that once.
   */
  private class SeekOnPreviewInfo(
    private val engine: BrowserPlayerEngine,
    private val target: Duration,
  ) : EngineListener {
    var armed: Boolean = false

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) = Unit

    override fun onPosition(position: Duration) = Unit

    override fun onPreviewInfo(info: PreviewInfo) {
      if (!armed) return
      armed = false
      engine.seekTo(target, SeekAccuracy.Exact)
    }
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
    // Only has to be queued ahead of the landing when the fence starts.
    const val FENCE_HOLD = 16
    const val HELD_READINGS = 3

    val HELD_TARGET: Duration = 1050.milliseconds

    // Inside the second group of pictures, so it lands on that group's sync sample rather than the held one's.
    val CHAINED_TARGET: Duration = 700.milliseconds
    val HELD_SYNC_SAMPLE: Duration = FIXTURE_SYNC_INTERVAL * 2
    val REBUILD_START: Duration = 300.milliseconds
    val SWAP_START: Duration = 400.milliseconds
    val SHORTENED_LENGTH: Duration = 1400.milliseconds

    // Two brightness amounts, so moving from one to the other is a parameter edit the preview takes in place.
    const val DIM = 0.4f
    const val BRIGHT = 1.4f
    val HOLD_POSITIONS: List<Duration> =
      listOf(CLIP_LENGTH - FIXTURE_FRAME_STEP, FIXTURE_SYNC_INTERVAL - FIXTURE_FRAME_STEP)

    // Ends a third of a slot past a slot, well clear of the fixture's last one.
    val RANGE: TimeRange =
      TimeRange.of(600.milliseconds + FIXTURE_FRAME_STEP / 3, 1100.milliseconds + FIXTURE_FRAME_STEP / 3)
  }
}
