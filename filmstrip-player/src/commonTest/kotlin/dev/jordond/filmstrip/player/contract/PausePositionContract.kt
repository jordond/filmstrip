package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.seekLandingOf
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration

/**
 * What a player reports once a pause has settled, run by [PlayerEngineContractTest] on every backend.
 *
 * The position stays where the pause put it, a frame read at it is a frame of its own slot, and a step of one frame
 * either way lands on the slots beside it. Where a backend can name the slot of the picture it has on screen apart from
 * its own position, [EngineUnderTest.shownAt], that slot is the one the position names too, so a caller that stops on
 * a picture and stamps the position it reads has the frame it was looking at. A backend says whether it can with
 * [EngineUnderTest.namesPictureShown], and one that cannot is held to the rest only, so the case says nothing of its
 * picture.
 *
 * Android does not answer here. The only stamp media3 gives for the frame on its surface is the one the position is
 * read from, so `AndroidPausePositionMediaTest` copies the pixels off the surface and holds them to the export of the
 * slot reported instead.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case plays and pauses in.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class PausePositionContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: Composition,
  private val rate: Int,
) {
  /**
   * Plays from the start and pauses once the playhead has passed each of [POINTS] of the way in.
   *
   * Once the engine is quiet the readings stop moving, the frame read at the playhead is the one for the slot it
   * rounds to, and where the backend can name the slot on screen, that is the slot the playhead names.
   */
  fun reportsASteadySlotAndTheOneOnScreenWhereTheBackendKnowsIt(): TestResult =
    pausedTest { subject, recorder, duration ->
      for (point in POINTS) {
        val at = duration * point
        recorder.pauseAfter(subject, at)
        val reported = recorder.playhead ?: Duration.ZERO

        settle()
        withClue("The playhead read $reported after a pause past $at, and then ${recorder.playhead}.") {
          recorder.playhead shouldBe reported
          val read = subject.engine.readback.awaitFrame(reported)
          // A backend stamps a frame with its own clock, so the slot it rounds to is what is compared.
          outputSlotOf(read.presentationTime, rate) shouldBe outputSlotOf(reported, rate)
          // The slot on screen is read off the platform, apart from the position, so a position that lags it by a slot
          // or two is caught here.
          val shown = subject.shownAt()
          withClue("Whether the backend named the picture it shows, which it said it does or does not") {
            (shown != null) shouldBe subject.namesPictureShown
          }
          if (shown != null) outputSlotOf(reported, rate) shouldBe outputSlotOf(shown, rate)
        }
      }
    }

  /**
   * Pauses once the playhead has passed the middle of the composition, then steps a frame forward, back, and back
   * again.
   *
   * Each step lands on the slot beside the one the pause reported, whichever it was.
   */
  fun aStepFromThePauseLandsOnTheNeighbours(): TestResult =
    pausedTest { subject, recorder, duration ->
      if (!subject.engine.features.supports(PlayerFeature.FrameStepping)) return@pausedTest
      recorder.pauseAfter(subject, duration / 2)
      val shown = outputSlotOf(recorder.playhead ?: Duration.ZERO, rate)

      for ((step, slot) in listOf(1 to shown + 1, -1 to shown, -1 to shown - 1)) {
        subject.engine.stepFrames(step)
        awaitContract("the step of $step to land on slot $slot") {
          recorder.playhead == outputSlotTime(slot, rate)
        }
      }
    }

  /**
   * Pauses once the playhead has passed the middle of the composition, then plays on.
   *
   * No reading after the play is behind the slot the pause reported.
   */
  fun aPlayAfterAPauseNeverReadsBehindIt(): TestResult =
    pausedTest { subject, recorder, duration ->
      recorder.pauseAfter(subject, duration / 3)
      val shown = seekLandingOf(recorder.playhead ?: Duration.ZERO, duration, rate)
      val from = recorder.playheads.size

      subject.engine.play()
      val past = shown + outputSlotTime(1, rate)
      awaitContract("the playhead to move on from $shown") { (recorder.playhead ?: Duration.ZERO) >= past }
      subject.engine.pause()
      subject.quiet()

      recorder.playheads.drop(from).forEach { seekLandingOf(it, duration, rate) shouldBeGreaterThanOrEqualTo shown }
    }

  // Plays from the start until the playhead is past [at], pauses and waits for the engine to go quiet.
  private suspend fun ContractRecorder.pauseAfter(
    subject: EngineUnderTest,
    at: Duration,
  ) {
    subject.engine.seekTo(Duration.ZERO, SeekAccuracy.Exact)
    awaitContract("the seek back to the start") { playhead == Duration.ZERO }
    subject.quiet()
    subject.engine.play()
    awaitContract("the playhead to pass $at") { (playhead ?: Duration.ZERO) >= at }
    subject.engine.pause()
    subject.quiet()
  }

  private suspend fun EngineUnderTest.quiet() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private fun pausedTest(body: suspend (EngineUnderTest, ContractRecorder, Duration) -> Unit): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: error("The engine reported no duration to pause within.")
        body(subject, recorder, duration)
      }
    }

  private companion object {
    val POINTS = listOf(0.25, 0.5, 0.75)
  }
}
