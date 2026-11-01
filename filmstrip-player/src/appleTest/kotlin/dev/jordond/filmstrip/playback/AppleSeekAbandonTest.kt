package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.playback.contract.ContractRecorder
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.frameStepLanding
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestResult
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Which seeks the engine stops waiting for when playback fails or the item goes, and which it still waits for.
 *
 * Each test holds the main queue first, which is where AVFoundation answers a seek on both platforms, so the seek is
 * still unanswered when the failure or the new item arrives. A failed item is not staged here, since no fixture makes
 * AVFoundation fail an item it has already loaded.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSeekAbandonTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a refused load leaves the loaded item's seek counted until it lands`(): TestResult =
    withSeekHeld { engine, _ ->
      engine.awaitComposition(unreadableComposition()).shouldBeInstanceOf<SetCompositionResult.Failure>()
      engine.fence.unansweredSeeks shouldBe 1

      engine.drainCallbacks()
      engine.fence.unansweredSeeks shouldBe 0
    }

  /**
   * The detached item still answers the seek once the main queue is served. That late answer is neither counted against
   * the item that replaced it nor taken as where playback is, so a frame step afterwards starts from the new item's
   * start rather than from where the old seek was going.
   */
  @Test
  fun `a new item abandons the seek its predecessor was holding`(): TestResult =
    withSeekHeld { engine, recorder ->
      val loads = engine.platformLoads
      val shorter = shorterComposition()
      engine.awaitComposition(shorter).shouldBeInstanceOf<SetCompositionResult.Success>()
      engine.platformLoads shouldBe loads + 1
      engine.fence.unansweredSeeks shouldBe 0

      engine.drainCallbacks()
      engine.fence.unansweredSeeks shouldBe 0

      val landing = appleExportLowering(shorter).frameStepLanding(Duration.ZERO, 1) ?: fail("no next slot")
      awaitContract("the new item to be ready to seek") { recorder.lastState.status == PlaybackStatus.Ready }
      engine.stepFrames(1)
      awaitContract("the step to move the playhead") { recorder.playhead != Duration.ZERO }
      recorder.playhead shouldBe landing
    }

  /**
   * Loads the fixture, holds the main queue, and hands a seek to the item before running [body].
   */
  private fun withSeekHeld(body: suspend (AvPlayerEngine, ContractRecorder) -> Unit): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the item to be ready to seek") { recorder.lastState.status == PlaybackStatus.Ready }
        engine.drainCallbacks()

        dispatch_async(dispatch_get_main_queue()) { usleep(HOLD_MICROSECONDS) }
        engine.seekTo(PROBE_POSITIONS.first(), SeekAccuracy.Exact)
        engine.fence.unansweredSeeks shouldBe 1
        body(engine, recorder)
      }
    }

  private fun unreadableComposition(): EditComposition =
    clipOf(MediaSource.of(appleFixtureClip() + ".absent"), CLIP_LENGTH)

  private fun shorterComposition(): EditComposition = clipOf(MediaSource.of(appleFixtureClip()), CLIP_LENGTH / 2)

  private fun clipOf(
    source: MediaSource,
    length: Duration,
  ): EditComposition =
    EditComposition(tracks = listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, length))))))

  private companion object {
    // Long enough to cover the load or the refusal the test makes while the seek is held.
    const val HOLD_MICROSECONDS = 500_000u
  }
}
