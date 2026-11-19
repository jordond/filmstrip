package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.frameStepLanding
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.rate
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Where a seek and a frame step on the AVFoundation engine report they landed, against where AVFoundation put the
 * item.
 *
 * The shared contract suite covers an exact seek between two frames. These cover what only this backend decides: the
 * time a relaxed seek settles on, the frame a step presents, and the last frame a seek past the end holds.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSeekLandingMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a relaxed seek reports the slot of the time the item landed on`() =
    withLoadedEngine { engine, recorder ->
      val at = MID_GOP_POSITION + FIXTURE_FRAME_STEP / 3
      engine.seekAndAwait(recorder, at, SeekAccuracy.Nearest)

      // A tolerant seek can settle on either side of the request, so the slot is read off where the item settled.
      val reported = recorder.seekCompletions.last().position
      reported shouldBe slotTime(outputSlotOf(engine.itemTime(), FIXTURE_FRAME_RATE))
      slotTime(outputSlotOf(reported, FIXTURE_FRAME_RATE)) shouldBe reported

      engine.drainCallbacks()
      recorder.playhead shouldBe reported
    }

  @Test
  fun `a frame step from a slot lands on the next slot`() =
    withLoadedEngine { engine, recorder ->
      val from = PROBE_POSITIONS.first()
      engine.seekAndAwait(recorder, from, SeekAccuracy.Exact)
      val landing = appleExportLowering(appleFixtureComposition()).frameStepLanding(from, 1) ?: fail("no next slot")

      engine.stepFrames(1)
      awaitContract("the step from $from to move the playhead") { recorder.playhead != from }

      recorder.playhead shouldBe landing
      engine.drainCallbacks()
      recorder.playhead shouldBe landing
    }

  @Test
  fun `a seek to the duration lands on the last slot`() =
    withLoadedEngine { engine, recorder ->
      val last = slotTime(outputSlotOf(CLIP_LENGTH, FIXTURE_FRAME_RATE) - 1)
      engine.seekAndAwait(recorder, CLIP_LENGTH, SeekAccuracy.Exact)

      recorder.seekCompletions.last().position shouldBe last
      recorder.playhead shouldBe last
      engine.drainCallbacks()
      recorder.playhead shouldBe last
    }

  @Test
  fun `a pause while paused keeps the landing`() =
    withLoadedEngine { engine, recorder ->
      engine.seekAndAwait(recorder, PROBE_POSITIONS.first() + FIXTURE_FRAME_STEP / 3, SeekAccuracy.Exact)
      val landing = recorder.seekCompletions.last().position

      engine.pause()
      engine.drainCallbacks()
      recorder.playhead shouldBe landing
    }

  // The native rate is slowed so the pause lands with the clock past the middle of a slot, where it rounds to the next
  // slot while the picture on screen is still the earlier slot's frame.
  @Test
  fun `a pause past the middle of a slot reports the next slot and moves the picture on to it`() =
    withLoadedEngine { engine, recorder ->
      val from = PROBE_POSITIONS.first()
      val slot = outputSlotOf(from, FIXTURE_FRAME_RATE)
      val next = slotTime(slot + 1)
      engine.seekAndAwait(recorder, from, SeekAccuracy.Exact)

      engine.play()
      engine.platformPlayer.rate = SLOW_RATE
      awaitContract("the clock to pass the middle of slot $slot") { engine.itemTime() >= from + PAST_MIDDLE }
      // Read just before the pause, since the settle's seek moves the item's clock as it is issued.
      val stopping = engine.itemTime()
      engine.pause()
      engine.drainCallbacks()

      withClue("The clock read $stopping as the pause was made, past the middle of slot $slot.") {
        (stopping < next) shouldBe true
        recorder.playhead shouldBe next
        outputSlotOf(engine.itemTime(), FIXTURE_FRAME_RATE) shouldBe slot + 1
        val read = engine.readback.awaitFrame(next)
        outputSlotOf(read.presentationTime, FIXTURE_FRAME_RATE) shouldBe slot + 1
      }
    }

  @Test
  fun `a pause before the middle of a slot reports the clock and leaves the picture`() =
    withLoadedEngine { engine, recorder ->
      val from = PROBE_POSITIONS.first()
      val slot = outputSlotOf(from, FIXTURE_FRAME_RATE)
      engine.seekAndAwait(recorder, from, SeekAccuracy.Exact)

      engine.play()
      engine.platformPlayer.rate = SLOW_RATE
      awaitContract("the clock to move into slot $slot") { engine.itemTime() >= from + BEFORE_MIDDLE }
      engine.pause()
      engine.drainCallbacks()
      val stopped = engine.itemTime()

      withClue("The clock stopped at $stopped, short of the middle of slot $slot.") {
        outputSlotOf(stopped, FIXTURE_FRAME_RATE) shouldBe slot
        recorder.playhead shouldBe stopped
      }
    }

  // At full rate the clock runs on furthest after pause() returns, and stops anywhere in a slot. A pause past the middle
  // settles the item onto the next slot, and its clock then reads that slot's time to the item's own timescale.
  @Test
  fun `a pause at full rate reports where the clock came to rest`() =
    withLoadedEngine { engine, recorder ->
      repeat(FULL_RATE_PAUSES) {
        engine.play()
        val from = engine.itemTime()
        awaitContract("the clock to run on from $from") { engine.itemTime() >= from + FIXTURE_FRAME_STEP * 3 }
        engine.pause()
        engine.drainCallbacks()

        val rested = engine.itemTime()
        val settled = slotTime(outputSlotOf(rested, FIXTURE_FRAME_RATE))
        withClue("The clock came to rest at $rested.") {
          recorder.playhead shouldBeIn listOf(rested, settled)
        }
      }
    }

  private fun withLoadedEngine(body: suspend (AvPlayerEngine, ContractRecorder) -> Unit) =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        body(engine, recorder)
      }
    }

  private suspend fun AvPlayerEngine.seekAndAwait(
    recorder: ContractRecorder,
    at: Duration,
    accuracy: SeekAccuracy,
  ) {
    val before = recorder.seekCompletions.size
    seekTo(at, accuracy)
    awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }
  }

  private fun AvPlayerEngine.itemTime(): Duration =
    platformPlayer.currentItem?.currentTime()?.toDuration() ?: fail("the player holds no item")

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, FIXTURE_FRAME_RATE)

  private companion object {
    // Slow enough that the clock spends most of a second in each slot, so the wait sees it inside the stretch it waits
    // for before it moves on.
    const val SLOW_RATE = 0.05f
    val PAST_MIDDLE: Duration = FIXTURE_FRAME_STEP * 6 / 10
    val BEFORE_MIDDLE: Duration = FIXTURE_FRAME_STEP / 10

    const val FULL_RATE_PAUSES = 5
  }
}
