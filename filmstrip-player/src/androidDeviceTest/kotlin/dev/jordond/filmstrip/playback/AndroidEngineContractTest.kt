package dev.jordond.filmstrip.playback

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestResult
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared engine contracts, run against a real `CompositionPlayer` on a device.
 *
 * [Interruption.OutputRouteChanged] is staged by handing the engine's own receiver the intent the
 * system sends. Everything filmstrip owns runs from there: the transport stops, the snapshot flips
 * and exactly one event is emitted. The delivery step ahead of it is Android's, and a test cannot
 * run it, since `ACTION_AUDIO_BECOMING_NOISY` is a protected broadcast that no process but the
 * system may send, adb shell included.
 *
 * What is not covered here:
 * - [Interruption.AudioFocusLost]. media3 owns the focus request and reports the loss as a
 *   `playWhenReady` reason, which is pinned in `Media3TransportTest`. Raising a real loss needs
 *   another app to take the session: a request from this process is granted without media3's own
 *   ever being told it lost, so there is nothing here to assert on.
 * - [Interruption.AppBackgrounded]. media3 does not watch the host's lifecycle and filmstrip
 *   registers no observer either, so nothing on Android raises it today.
 * - [Interruption.IncomingCall] and [Interruption.MediaServicesReset], which need real telephony and
 *   a dead media daemon and so are in `UNSTAGEABLE_INTERRUPTIONS` for every platform.
 * - A real route change, which needs hardware to be unplugged.
 */
class AndroidEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = androidEngine(scope)

  override val fixture: EditComposition = androidFixtureComposition()

  override val stageableInterruptions: Set<Interruption> =
    setOf(Interruption.OutputRouteChanged)

  @Test
  fun itClaimsWhatCompositionPlayerReallyOffersAndNothingItDoesNot() =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) {
        val features = subject.engine.features.all()

        features shouldContain PlayerFeature.FrameReadback
        features shouldContain PlayerFeature.FrameStepping
        features shouldContain PlayerFeature.LiveParameterRedraw
        features shouldContain PlayerFeature.AudioMonitoring
        // COMMAND_SET_SPEED_AND_PITCH is absent from CompositionPlayer's available set.
        features shouldNotContain PlayerFeature.PlaybackSpeed

        // Read off the display rather than typed here, since the same engine is right to claim it
        // on a panel that shows HDR and wrong to claim it on one that does not.
        features.contains(PlayerFeature.HdrPreview) shouldBe displayAdvertisesHdr()

        // What a surface attaches itself to, which on this backend has to be the player itself.
        subject.engine.nativePlayer.shouldBeInstanceOf<CompositionPlayer>()
      }
    }

  @Test
  fun disposingTwiceIsHarmlessAndDisposingMidLoadSupersedesTheLoad() =
    contractTest { scope ->
      val settled = createEngine(scope)
      settled.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
      settled.engine.dispose()
      settled.engine.dispose()

      val loading = createEngine(scope)
      val outcome =
        suspendCancellableCoroutine { continuation ->
          loading.engine.setComposition(SetCompositionRequest(fixture)) { result ->
            if (continuation.isActive) continuation.resume(result)
          }
          loading.engine.dispose()
        }

      outcome.shouldBeInstanceOf<SetCompositionResult.Superseded>()
    }

  /**
   * A relaxed seek lands where an exact one would, which is the clamp made visible.
   *
   * Asserted against [SeekAccuracy]'s own promise rather than against a frame index typed here: the
   * engine reports the accuracy it really ran at, and the playhead lands on the time that was asked
   * for rather than on a sync sample before it.
   */
  @Test
  fun aRelaxedSeekRunsExactSinceMedia3OffersNoTolerance() =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        subject.engine.seekTo(OFF_GRID, SeekAccuracy.Nearest)
        awaitContract("the relaxed seek to land") { recorder.seekCompletions.isNotEmpty() }

        // The time that was asked for, not the sync sample before it, which is what a backend
        // honouring Nearest natively would have landed on.
        subject.engine.lastSeekAccuracy shouldBe SeekAccuracy.Exact
        subject.engine.platformPosition shouldBe OFF_GRID
      }
    }

  /**
   * A composition running out is an ending, never an external pause.
   *
   * media3 reports the same `onPlayWhenReadyChanged(false)` for both, told apart only by a reason,
   * so this is the one that would have every clip that plays through read as an interruption.
   */
  @Test
  fun playingThroughToTheEndReportsAnEndingAndNoExternalChange(): TestResult =
    contractTest { scope ->
      val subject = androidEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }

        subject.engine.seekTo(CLIP_LENGTH - NEAR_THE_END, SeekAccuracy.Exact)
        awaitContract("the seek near the end to land") { recorder.seekCompletions.isNotEmpty() }
        subject.engine.play()

        awaitContract("the composition to reach its end") {
          recorder.events.any { it is PlaybackEvent.Ended }
        }
        settle()

        recorder.externalChanges.shouldBeEmpty()
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  /**
   * A source nothing can read fails the load rather than leaving the caller waiting.
   */
  @Test
  fun aSourceThatCannotBeReadFailsTheLoadAndReportsAnError() = failingLoad { androidFixtureBrokenComposition() }

  /**
   * The same, for a planner that threw rather than refusing.
   *
   * Planning reaches a prober, a resolver and a device query, and under load any of them can throw.
   * An engine that only handled a refusal left the caller suspended forever and published nothing.
   */
  @Test
  fun aPlannerThatThrewFailsTheLoadAndReportsAnError() = failingLoad(::throwingEngine) { fixture }

  private fun failingLoad(
    build: (CoroutineScope) -> AndroidEngineUnderTest = ::androidEngine,
    composition: () -> EditComposition,
  ) = contractTest { scope ->
    val subject = build(scope)
    withEngine(subject.engine) { recorder ->
      val outcome = subject.engine.awaitComposition(composition())

      outcome.shouldBeInstanceOf<SetCompositionResult.Failure>()
      awaitContract("the status to leave Idle") { recorder.lastState.status is PlaybackStatus.Error }
      recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldNotBeEmpty()
      recorder.lastState.hasComposition shouldBe false
    }
  }

  private fun throwingEngine(scope: CoroutineScope): AndroidEngineUnderTest =
    AndroidEngineUnderTest(scope, ThrowingPlanner())

  private fun androidEngine(scope: CoroutineScope): AndroidEngineUnderTest = AndroidEngineUnderTest(scope)

  /**
   * What the platform says the display can show, read the way a host would read it.
   */
  private fun displayAdvertisesHdr(): Boolean {
    val manager = contractContext().getSystemService(DisplayManager::class.java) ?: return false
    val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    return display.mode.supportedHdrTypes.isNotEmpty()
  }

  private companion object {
    // Between two frames of the fixture's 30fps grid, so an exact landing and a sync-sample landing
    // are different times rather than the same one.
    val OFF_GRID: Duration = 717.milliseconds

    val NEAR_THE_END: Duration = 300.milliseconds
  }
}
