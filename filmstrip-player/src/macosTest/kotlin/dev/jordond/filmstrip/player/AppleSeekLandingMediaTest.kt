package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.frameStepLanding
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
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

      // On the macOS host a tolerant seek reads back at the time it asked for, so this is the exact landing's slot.
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
}
