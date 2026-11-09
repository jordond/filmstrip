package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
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
  private val fixture: Composition,
  private val unreadable: Composition,
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

  /**
   * Plays, has a load's item fail, then loads something readable, and checks the play from before the failure happens.
   *
   * @param createFailing Builds an engine primed to fail on one edit.
   */
  fun playOutlivesAFailedItem(createFailing: (CoroutineScope) -> FailingItem): TestResult =
    contractTest { scope ->
      val failing = createFailing(scope)
      val engine = failing.subject.engine
      withEngine(engine) { recorder ->
        engine.play()

        failing.awaitFailureOf(failing.composition, recorder)
        recorder.lastState.playWhenReady shouldBe true

        engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()

        recorder.awaitPlayheadMoving()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  /**
   * Plays, has a load's item fail, puts its source back, then loads the same edit again and checks that one plays.
   *
   * @param createFailing Builds an engine primed to fail on one edit.
   */
  fun theSameEditPlaysAfterAFailedItem(createFailing: (CoroutineScope) -> FailingItem): TestResult =
    contractTest { scope ->
      val failing = createFailing(scope)
      val engine = failing.subject.engine
      withEngine(engine) { recorder ->
        engine.play()
        failing.awaitFailureOf(failing.composition, recorder)
        failing.restoreSource()

        engine.awaitComposition(failing.composition).shouldBeInstanceOf<SetCompositionResult.Success>()

        recorder.awaitPlayheadMoving()
        recorder.lastState.status.shouldNotBeInstanceOf<PlaybackStatus.Error>()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  /**
   * Has a load's item fail, puts its source back, plays, then edits only a parameter and checks that edit plays.
   *
   * @param createFailing Builds an engine primed to fail on one edit.
   */
  fun aParameterEditPlaysAfterAFailedItem(createFailing: (CoroutineScope) -> FailingItem): TestResult =
    contractTest { scope ->
      val failing = createFailing(scope)
      val engine = failing.subject.engine
      val first = failing.composition.withEffects(listOf(Brightness(FAILED_BRIGHTNESS)))
      val retuned = failing.composition.withEffects(listOf(Brightness(RETUNED_BRIGHTNESS)))
      diff(first, retuned) shouldBe CompositionDiff.ParametersOnly
      withEngine(engine) { recorder ->
        failing.awaitFailureOf(first, recorder)
        failing.restoreSource()
        engine.play()

        engine.awaitComposition(retuned).shouldBeInstanceOf<SetCompositionResult.Success>()

        recorder.awaitPlayheadMoving()
        recorder.lastState.status.shouldNotBeInstanceOf<PlaybackStatus.Error>()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  /**
   * Loads, has a further edit refused, then edits only a parameter of the loaded one and checks the refusal lifts and
   * a play goes ahead.
   */
  fun aParameterEditLiftsARefusalOverAStandingGraph(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      val retuned = fixture.withEffects(listOf(Brightness(RETUNED_BRIGHTNESS)))
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the load to be presentable") { recorder.lastState.hasComposition }
        subject.engine.awaitComposition(unreadable).shouldBeInstanceOf<SetCompositionResult.Failure>()
        recorder.lastState.status.shouldBeInstanceOf<PlaybackStatus.Error>()
        subject.engine.play()

        subject.engine.awaitComposition(retuned).shouldBeInstanceOf<SetCompositionResult.Success>()

        awaitContract("the status to leave the refusal") { recorder.lastState.status !is PlaybackStatus.Error }
        recorder.awaitPlayheadMoving()
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  private suspend fun FailingItem.awaitFailureOf(
    edit: Composition,
    recorder: ContractRecorder,
  ) {
    val answer = subject.engine.awaitComposition(edit)
    if (refusedAtLoad) {
      answer.shouldBeInstanceOf<SetCompositionResult.Failure>()
    } else {
      answer.shouldBeInstanceOf<SetCompositionResult.Success>()
    }
    awaitContract("the item to fail") { recorder.lastState.status is PlaybackStatus.Error }
  }

  // Two readings in a row past the start, so one reading a load or a seek happened to report does not count as moving.
  private suspend fun ContractRecorder.awaitPlayheadMoving() {
    awaitContract("the playhead to leave the start") { (playhead ?: Duration.ZERO) > Duration.ZERO }
    val first = playheads.last()
    awaitContract("the playhead to move on from $first") { playheads.last() > first }
  }

  private companion object {
    const val FAILED_BRIGHTNESS = 0.4f
    const val RETUNED_BRIGHTNESS = 1.4f
  }
}
