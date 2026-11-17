package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration

/**
 * What a seek reports once it completes, run by [PlayerEngineContractTest] on every backend.
 *
 * Every seek issued completes exactly once, an exact one reports the slot it was aimed at, and a relaxed one reports
 * a slot within the backend's tolerance of its target. Either way the playhead reads the slot the completion named.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case seeks inside.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class SeekLandingContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: Composition,
  private val rate: Int,
) {
  /**
   * Issues a burst of one seek, then bursts big enough that most requests are superseded before the platform reaches
   * them.
   *
   * A superseded request still owes the caller a completion, so each burst completes as many times as it has seeks.
   */
  fun everySeekCompletesOnce(): TestResult =
    seekTest { subject, recorder, duration ->
      for (burst in BURST_SIZES) {
        val before = recorder.seekCompletions.size
        spreadOver(duration, burst).forEach { subject.engine.seekTo(it, SeekAccuracy.Exact) }

        awaitContract("$burst seeks to complete") { recorder.seekCompletions.size - before >= burst }
        awaitContract("the seeking axis to settle after $burst seeks") { !recorder.lastState.isSeeking }
        subject.quiet()

        recorder.seekCompletions.size - before shouldBe burst
      }
    }

  /**
   * Seeks exactly to a point a third of a slot past its slot, [roundsDownAt] of the way in, and to one a third short
   * of it, [roundsUpAt] of the way in.
   *
   * A landing that rounds either way wrong lands a slot off, so each completion and the playhead name the slot the
   * target is in.
   */
  fun anExactSeekReportsItsSlot(
    roundsDownAt: Double,
    roundsUpAt: Double,
  ): TestResult =
    seekTest { subject, recorder, duration ->
      val positions =
        listOf(offGrid(duration, roundsDownAt, rate, thirds = 1), offGrid(duration, roundsUpAt, rate, thirds = -1))
      for (at in positions) {
        val landing = outputSlotTime(outputSlotOf(at, rate), rate)
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(at, SeekAccuracy.Exact)
        awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }

        recorder.seekCompletions.last().position shouldBe landing
        recorder.playhead shouldBe landing

        subject.quiet()
        recorder.playhead shouldBe landing
      }
    }

  /**
   * Seeks relaxed to a point [at] of the way in, a third of a slot past its slot.
   *
   * The frame shown can sit on either side of the target, so the slot reported is a whole slot within [tolerance] of
   * it, and the playhead reads that slot once the engine is quiet.
   */
  fun aRelaxedSeekReportsItsSlot(
    at: Double,
    tolerance: Duration,
  ): TestResult =
    seekTest { subject, recorder, duration ->
      val target = offGrid(duration, at, rate, thirds = 1)
      val earliest = outputSlotTime(outputSlotOf((target - tolerance).coerceAtLeast(Duration.ZERO), rate), rate)
      val latest = outputSlotTime(outputSlotOf(target + tolerance, rate), rate)
      val before = recorder.seekCompletions.size
      subject.engine.seekTo(target, SeekAccuracy.Nearest)
      awaitContract("the relaxed seek to $target to complete") { recorder.seekCompletions.size > before }

      val reported = recorder.seekCompletions.last().position
      outputSlotTime(outputSlotOf(reported, rate), rate) shouldBe reported
      reported shouldBeGreaterThanOrEqualTo earliest
      reported shouldBeLessThanOrEqualTo latest

      subject.quiet()
      recorder.playhead shouldBe reported
    }

  private suspend fun EngineUnderTest.quiet() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private fun seekTest(body: suspend (EngineUnderTest, ContractRecorder, Duration) -> Unit): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: error("The engine reported no duration to seek within.")
        body(subject, recorder, duration)
      }
    }

  private companion object {
    val BURST_SIZES = listOf(1, 2, 5, 9)

    /**
     * [count] positions spread evenly across `(0, duration)`, touching neither end.
     *
     * Zero and the duration are the two a backend special-cases, and a suite that visited only those would agree with
     * an engine that handles nothing in between.
     */
    fun spreadOver(
      duration: Duration,
      count: Int,
    ): List<Duration> = (1..count).map { step -> duration * (step / (count + 1.0)) }
  }
}
