package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration

/**
 * What a transport command given before the first composition has loaded does, run by [PlayerEngineContractTest] on
 * every backend.
 *
 * `play` promises pixels start moving once data is available, so a case that plays reads the playhead moving. Reading
 * `isPlaying` alone passes on an engine that reports it with its clock stopped.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture A composition the backend can load.
 * @param unreadable A composition the backend refuses to load.
 */
internal class EarlyTransportContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: EditComposition,
  private val unreadable: EditComposition,
) {
  /**
   * Plays with nothing loaded, then loads, and checks nothing plays until the load gives the playhead somewhere to go.
   */
  fun playMovesThePlayheadOnceLoaded(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.play()
        recorder.lastState.playWhenReady shouldBe true
        recorder.lastState.isPlaying shouldBe false

        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()

        recorder.awaitPlayheadMoving()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  /**
   * Takes a play back before anything has loaded, then loads, and checks the playhead stays on the start.
   */
  fun pauseHoldsThePlayheadOnTheStart(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.play()
        subject.engine.pause()

        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the load to be presentable") { recorder.lastState.hasComposition }
        awaitStep("the engine to go quiet") { subject.awaitQuiet() }
        settle()

        recorder.lastState.playWhenReady shouldBe false
        recorder.lastState.isPlaying shouldBe false
        recorder.playheads.forEach { it shouldBe Duration.ZERO }
      }
    }

  /**
   * Has the first load refused, and checks the status goes on saying so once the platform has finished reacting to it.
   */
  fun refusalStaysOnTheStatus(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(unreadable).shouldBeInstanceOf<SetCompositionResult.Failure>()
        awaitStep("the engine to go quiet") { subject.awaitQuiet() }
        settle()

        recorder.lastState.status.shouldBeInstanceOf<PlaybackStatus.Error>()
      }
    }

  /**
   * Has the first load refused, then loads something readable, and checks a play from before that load still happens.
   *
   * @param playFirst Whether the play comes ahead of the refused load, rather than after it.
   */
  fun playOutlivesARefusedLoad(playFirst: Boolean): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        if (playFirst) subject.engine.play()

        subject.engine.awaitComposition(unreadable).shouldBeInstanceOf<SetCompositionResult.Failure>()
        if (!playFirst) subject.engine.play()
        recorder.lastState.playWhenReady shouldBe true

        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()

        recorder.awaitPlayheadMoving()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  // Two readings in a row past the start, so one reading a load or a seek happened to report does not count as moving.
  private suspend fun ContractRecorder.awaitPlayheadMoving() {
    awaitContract("the playhead to leave the start") { (playhead ?: Duration.ZERO) > Duration.ZERO }
    val first = playheads.last()
    awaitContract("the playhead to move on from $first") { playheads.last() > first }
  }
}
