package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.ShuttleEdge
import dev.jordond.filmstrip.player.internal.shuttleBoundsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * What a shuttle shows and reports, run by [PlayerEngineContractTest] on every backend.
 *
 * An engine that claims [PlayerFeature.Shuttle] moves the playhead frame by frame in the direction of the rate, holds
 * at each edge and resumes from the frame showing. One that does not claim it ignores both calls. Either way an
 * invalid rate throws.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case shuttles through.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class ShuttleContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: Composition,
  private val rate: Int,
) {
  /**
   * Shuttles forwards from the first frame to the end, then backwards to the start.
   *
   * Each way the playhead only moves in the direction of the rate, on the frame grid and inside the composition, more
   * than one frame is shown for each second it runs, and the edge is reported once with the frame held. Nothing is
   * rebuilt from entry to exit.
   */
  fun runsToEachEdgeAndHolds(): TestResult =
    shuttleTest { subject, recorder, duration ->
      val engine = subject.engine
      val bounds = shuttleBoundsOf(duration, rate, playbackRange = null)
      val loads = subject.platformLoads
      recorder.seekExactly(subject, bounds.start)

      val forwards = recorder.shuttleUntilEdge(subject, FAST)
      forwards.zipWithNext().forEach { (earlier, later) -> later shouldBeGreaterThanOrEqualTo earlier }
      forwards.shouldSitInside(bounds)
      recorder.edges.map { it.edge } shouldBe listOf(ShuttleEdge.End)
      recorder.edges.last().position shouldBe forwards.last()
      recorder.lastState.shuttleRate shouldBe FAST

      settle()
      recorder.edges.map { it.edge } shouldBe listOf(ShuttleEdge.End)
      recorder.playhead shouldBe forwards.last()

      val backwards = recorder.shuttleUntilEdge(subject, -FAST)
      backwards.zipWithNext().forEach { (earlier, later) -> later shouldBeLessThanOrEqualTo earlier }
      backwards.shouldSitInside(bounds)
      recorder.edges.map { it.edge } shouldBe listOf(ShuttleEdge.End, ShuttleEdge.Start)
      recorder.edges.last().position shouldBe bounds.start

      engine.endShuttle()
      awaitStep("the engine to go quiet") { subject.awaitQuiet() }
      recorder.lastState.shuttleRate shouldBe null
      recorder.lastState.playWhenReady shouldBe false
      recorder.playhead shouldBe bounds.start
      subject.platformLoads shouldBe loads
    }

  /**
   * Shuttles backwards from the middle while paused, then ends.
   *
   * The player stays paused on a frame no later than the last one the shuttle reported.
   */
  fun aPausedShuttleEndsPausedOnTheFrameShowing(): TestResult =
    shuttleTest { subject, recorder, duration ->
      val engine = subject.engine
      val middle = outputSlotTime(outputSlotOf(duration / 2, rate), rate)
      recorder.seekExactly(subject, middle)

      val from = recorder.playheads.size
      engine.beginShuttle(-SLOW)
      awaitContract("the shuttle to show two frames before $middle") {
        recorder.playheads
          .drop(from)
          .filter { it < middle }
          .distinct()
          .size >= 2
      }
      val shown = recorder.playheads.last()

      engine.endShuttle()
      awaitStep("the engine to go quiet") { subject.awaitQuiet() }
      recorder.lastState.shuttleRate shouldBe null
      recorder.lastState.playWhenReady shouldBe false
      recorder.playhead?.shouldBeLessThanOrEqualTo(shown) ?: fail("The engine reported no playhead.")
      recorder.edges.shouldBeEmpty()
    }

  /**
   * Shuttles forwards from the start while playing, then ends.
   *
   * The shuttle reads as paused while it runs, and ending it plays on from the last frame it showed, never from
   * before it.
   */
  fun aPlayingShuttleEndsPlayingFromTheFrameShowing(): TestResult =
    shuttleTest { subject, recorder, duration ->
      val engine = subject.engine
      recorder.seekExactly(subject, Duration.ZERO)
      engine.play()
      awaitContract("playback to start") { recorder.lastState.isPlaying }

      val from = recorder.playheads.size
      engine.beginShuttle(SLOW)
      recorder.lastState.playWhenReady shouldBe false
      recorder.lastState.shuttleRate shouldBe SLOW
      awaitContract("the shuttle to show three frames") {
        recorder.playheads
          .drop(from)
          .distinct()
          .size >= 3
      }
      val shown = recorder.playheads.last()

      val resumed = recorder.playheads.size
      engine.endShuttle()
      recorder.lastState.playWhenReady shouldBe true
      recorder.lastState.shuttleRate shouldBe null
      awaitContract("playback to move on from $shown") { recorder.playheads.last() > shown }
      engine.pause()
      awaitStep("the engine to go quiet") { subject.awaitQuiet() }

      withClue("Playheads after the shuttle ended on $shown, in a composition $duration long.") {
        recorder.playheads.drop(resumed).forEach { it shouldBeGreaterThanOrEqualTo shown }
      }
    }

  /**
   * Hands every engine a rate no shuttle takes.
   *
   * Each one throws, whether or not the engine claims the feature, and no shuttle starts.
   */
  fun anInvalidRateThrows(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }

        for (invalid in listOf(0f, Float.NaN, Float.NEGATIVE_INFINITY, 32.5f, -33f)) {
          assertFailsWith<IllegalArgumentException> { subject.engine.beginShuttle(invalid) }
        }
        recorder.lastState.shuttleRate shouldBe null
      }
    }

  // Runs [body] on an engine that claims the feature. On one that does not, checks instead that both calls do nothing.
  private fun shuttleTest(
    body: suspend (subject: EngineUnderTest, recorder: ContractRecorder, duration: Duration) -> Unit,
  ): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("The engine reported no duration to shuttle within.")

        if (subject.engine.features.supports(PlayerFeature.Shuttle)) {
          body(subject, recorder, duration)
        } else {
          ignoresBothCalls(subject, recorder)
        }
      }
    }

  private suspend fun ignoresBothCalls(
    subject: EngineUnderTest,
    recorder: ContractRecorder,
  ) {
    val mark = recorder.mark()
    val events = recorder.events.size
    val playhead = recorder.playhead

    subject.engine.beginShuttle(FAST)
    settle()
    subject.engine.endShuttle()
    awaitStep("the engine to go quiet") { subject.awaitQuiet() }

    recorder.statesSince(mark).forEach { it.shuttleRate shouldBe null }
    recorder.lastState.shuttleRate shouldBe null
    recorder.events.drop(events).shouldBeEmpty()
    recorder.playhead shouldBe playhead
  }

  private suspend fun ContractRecorder.seekExactly(
    subject: EngineUnderTest,
    position: Duration,
  ) {
    val before = seekCompletions.size
    subject.engine.seekTo(position, SeekAccuracy.Exact)
    awaitContract("the seek to $position to complete") { seekCompletions.size > before }
    awaitStep("the engine to go quiet") { subject.awaitQuiet() }
  }

  // Shuttles at [shuttleRate] until one more edge is reported, and returns every playhead reported on the way.
  private suspend fun ContractRecorder.shuttleUntilEdge(
    subject: EngineUnderTest,
    shuttleRate: Float,
  ): List<Duration> {
    val from = playheads.size
    val reached = edges.size
    val began = TimeSource.Monotonic.markNow()
    subject.engine.beginShuttle(shuttleRate)
    awaitContract("the shuttle at $shuttleRate to reach an edge") { edges.size > reached }
    val ran = began.elapsedNow()

    val shown = playheads.drop(from)
    withClue("Frames $shown over $ran at a rate of $shuttleRate.") {
      shown
        .distinct()
        .size
        .toDouble() shouldBeGreaterThan maxOf(1.0, ran.toDouble(DurationUnit.SECONDS))
    }
    return shown
  }

  private fun List<Duration>.shouldSitInside(bounds: ClosedRange<Duration>) {
    forEach { shown ->
      outputSlotTime(outputSlotOf(shown, rate), rate) shouldBe shown
      shown shouldBeGreaterThanOrEqualTo bounds.start
      shown shouldBeLessThanOrEqualTo bounds.endInclusive
    }
  }

  private val ContractRecorder.edges: List<PlaybackEvent.ShuttleReachedEdge>
    get() = events.filterIsInstance<PlaybackEvent.ShuttleReachedEdge>()

  private companion object {
    // Quick enough to cross a fixture a few seconds long inside the suite's budget.
    const val FAST = 4f

    // Slow enough that an engine shows several frames before a short fixture runs out.
    const val SLOW = 1f
  }
}
