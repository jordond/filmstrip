package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the desktop engine's fences wait out before they return.
 *
 * Each test holds a scripted stream at a [StreamGate], starts the fence while it is held and only then opens the gate,
 * so whatever the held pump goes on to report has to be in before the fence returns. The test body shares the engine's
 * dispatcher, which runs one coroutine at a time, so a fence that returned early has returned before the held pump ran
 * again.
 */
class FfmpegPumpDrainTest {
  @Test
  fun `a failure read off the process late is reported before the engine goes idle`() =
    contractTest { scope ->
      val exit = StreamGate()
      val planner = ScriptedPlanner { ScriptedStream(it, FRAMES, status = FAILED, stderr = STDERR, exitGate = exit) }

      withPlaying(scope, planner) { engine, recorder ->
        awaitContract("the status read to be held") { exit.isReached }
        fenceAcross(exit, "every pump to finish") { engine.awaitIdle() }

        recorder.events
          .filterIsInstance<PlaybackEvent.Failed>()
          .single()
          .error
          .shouldBeInstanceOf<PlaybackError.Underlying>()
          .platformCode shouldBe FAILED
      }
    }

  @Test
  fun `an end read off the process late is reported before the engine goes idle`() =
    contractTest { scope ->
      val exit = StreamGate()
      val planner = ScriptedPlanner { ScriptedStream(it, FRAMES, status = 0, exitGate = exit) }

      withPlaying(scope, planner) { engine, recorder ->
        awaitContract("the status read to be held") { exit.isReached }
        fenceAcross(exit, "every pump to finish") { engine.awaitIdle() }

        recorder.events.filterIsInstance<PlaybackEvent.Ended>().size shouldBe 1
        recorder.lastState.status shouldBe PlaybackStatus.Ended
      }
    }

  // Two callers wait at once, so the second finds the stopped pump the first is already joining and has to wait it out
  // as well rather than finding nothing left to wait on.
  @Test
  fun `a stopped pump still reading has finished before the replaced pumps are drained`() =
    contractTest { scope ->
      val read = StreamGate()
      var opens = 0
      val planner =
        ScriptedPlanner {
          if (opens++ == 0) ScriptedStream(it, frames = 1, status = FAILED, readGate = read) else endless(it)
        }
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the read after the first frame to be held") { read.isReached }
        val before = recorder.seekCompletions.size
        engine.seekTo(MID_GOP_POSITION, SeekAccuracy.Exact)
        awaitContract("the seek to land") { recorder.seekCompletions.size > before }
        planner.opened.first().closed shouldBe true

        coroutineScope {
          val first = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitRetired() }
          val second = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitRetired() }
          read.open()
          awaitStep("the second caller to see the stopped pump finish") { second.await() }
          read.isPassed shouldBe true
          awaitStep("the first caller to return") { first.await() }
        }
        recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldBeEmpty()
      }
    }

  // Held in a scrub, so the wrap is a relaxed seek that waits out the scrub debounce before it opens its pump. The pump
  // that wrapped has finished long before then, which leaves the fence a seek to catch after the pump it waited on.
  @Test
  fun `a wrap handed over by a finishing pump lands before the engine goes idle`() =
    contractTest { scope ->
      val exit = StreamGate()
      val range = TimeRange.from(LOOP_START)
      var opens = 0
      val planner =
        ScriptedPlanner {
          if (opens++ == 0) ScriptedStream(it, LOOP_FRAMES, status = 0, exitGate = exit) else ScriptedStream(it, 1, 0)
        }
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine
          .awaitComposition(jvmFixtureComposition(), startAt = LOOP_START)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val wrap = loopWrapOf(range, duration, FIXTURE_RATE) ?: fail("$range has nothing to loop")
        wrap.reachesEnd shouldBe true
        (wrap.lastSlot - wrap.firstSlot + 1) shouldBe LOOP_FRAMES

        engine.setLoopRange(range)
        engine.beginScrub()
        engine.play()
        awaitContract("the status read to be held") { exit.isReached }
        fenceAcross(exit, "the wrap's pump to finish") { engine.awaitIdle() }

        recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().map { it.range } shouldBe listOf(range)
        recorder.seekCompletions.map { it.position } shouldBe listOf(outputSlotTime(wrap.firstSlot, FIXTURE_RATE))
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().size shouldBe 1
        planner.opened.size shouldBe 2
      }
    }

  private suspend fun withPlaying(
    scope: CoroutineScope,
    planner: ScriptedPlanner,
    body: suspend (FfmpegPlayerEngine, ContractRecorder) -> Unit,
  ) {
    val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())
    withEngine(engine) { recorder ->
      engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the first frame to land") { recorder.playhead != null }
      engine.play()
      body(engine, recorder)
    }
  }

  /**
   * Starts [fence] while [gate] still holds its stream, then opens the gate and returns once [fence] does.
   */
  private suspend fun fenceAcross(
    gate: StreamGate,
    description: String,
    fence: suspend () -> Unit,
  ) = coroutineScope {
    val fenced = async(start = CoroutineStart.UNDISPATCHED) { awaitStep(description) { fence() } }
    gate.open()
    fenced.await()
  }

  private fun endless(at: Duration): ScriptedStream = ScriptedStream(at, Long.MAX_VALUE, status = FAILED)

  private companion object {
    // Few enough that a play-through ends well inside the budget.
    const val FRAMES = 6L

    // Any status but zero is a failure, and one is what ffmpeg exits with on a bad input.
    const val FAILED = 1

    const val STDERR = "scripted.mp4: No such file or directory"

    // Half a second before the fixture's end, so a pass is fifteen frames at its rate.
    val LOOP_START = 1_000.milliseconds
    const val LOOP_FRAMES = 15L
  }
}
