package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
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
import kotlinx.coroutines.test.TestResult
import platform.darwin.dispatch_get_main_queue
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
class AppleSeekAbandonMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a refused load leaves the loaded item's seek counted until it lands`(): TestResult =
    withSeekHeld { engine, _, mainQueue ->
      engine.awaitComposition(unreadableComposition()).shouldBeInstanceOf<SetCompositionResult.Failure>()
      engine.fence.unansweredSeeks shouldBe 1

      mainQueue.open()
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
    withSeekHeld { engine, recorder, mainQueue ->
      val loads = engine.platformLoads
      val shorter = shorterComposition()
      engine.awaitComposition(shorter).shouldBeInstanceOf<SetCompositionResult.Success>()
      engine.platformLoads shouldBe loads + 1
      engine.fence.unansweredSeeks shouldBe 0

      mainQueue.open()
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
   *
   * The main queue stays held until [body] opens the gate it is handed, or until [body] ends.
   */
  private fun withSeekHeld(body: suspend (AvPlayerEngine, ContractRecorder, QueueGate) -> Unit): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the item to be ready to seek") { recorder.lastState.status == PlaybackStatus.Ready }
        engine.drainCallbacks()

        val mainQueue = QueueGate()
        mainQueue.hold(dispatch_get_main_queue())
        try {
          engine.seekTo(PROBE_POSITIONS.first(), SeekAccuracy.Exact)
          engine.fence.unansweredSeeks shouldBe 1
          body(engine, recorder, mainQueue)
        } finally {
          mainQueue.open()
        }
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
}
