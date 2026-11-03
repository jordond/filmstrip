package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How the desktop engine reports a pump whose process ran out, told apart by the process's exit status.
 *
 * The streams are scripted rather than spawned, so each test picks the frames and the status the process ends with.
 * The composition is still lowered by a real ffmpeg, which is what gives the engine a plan to open streams on.
 */
class FfmpegPreviewFailureTest {
  @Test
  fun `frames then a non-zero exit fail playback with the process's stderr`() =
    contractTest { scope ->
      val planner = ScriptedPlanner { ScriptedStream(it, frames = FRAMES, status = FAILED, stderr = STDERR) }

      withPlaying(scope, planner) { engine, recorder ->
        awaitContract("playback to fail", timeout = PLAY_BUDGET) { recorder.failures.isNotEmpty() }
        engine.awaitPumpsDone()

        val error =
          recorder.failures
            .single()
            .error
            .shouldBeInstanceOf<PlaybackError.Underlying>()
        error.platformCode shouldBe FAILED
        error.message shouldContain STDERR
        recorder.lastState.status shouldBe PlaybackStatus.Error(error)
        recorder.lastState.playWhenReady shouldBe false
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        engine.platformLoads shouldBe 1
      }
    }

  @Test
  fun `frames then a clean exit end playback`() =
    contractTest { scope ->
      val planner = ScriptedPlanner { ScriptedStream(it, frames = FRAMES, status = 0, stderr = STDERR) }

      withPlaying(scope, planner) { engine, recorder ->
        awaitContract("playback to end", timeout = PLAY_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        engine.awaitPumpsDone()

        recorder.failures.shouldBeEmpty()
        recorder.lastState.status shouldBe PlaybackStatus.Ended
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .single()
          .finalPosition shouldBe recorder.lastState.duration
      }
    }

  // Nothing is playing, so the failure is the only thing that says the process died before its first frame.
  @Test
  fun `a process that fails before its first frame fails while paused`() =
    contractTest { scope ->
      val planner = ScriptedPlanner { ScriptedStream(it, frames = 0, status = FAILED, stderr = STDERR) }
      val engine = FfmpegPlayerEngine(parent = scope, planner = planner, config = PlayerConfig())

      withEngine(engine) { recorder ->
        engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the pump to fail") { recorder.failures.isNotEmpty() }
        engine.awaitPumpsDone()

        recorder.failures
          .single()
          .error.message shouldContain STDERR
        recorder.lastState.status.shouldBeInstanceOf<PlaybackStatus.Error>()
      }
    }

  // Every scripted stream reads back as killed once closed, so only the engine can tell its own seek from a failure.
  @Test
  fun `a seek mid-stream reports neither a failure nor an end`() =
    contractTest { scope ->
      val planner = ScriptedPlanner { ScriptedStream(it, frames = Long.MAX_VALUE, status = FAILED, stderr = STDERR) }
      val landing = outputSlotTime(outputSlotOf(MID_GOP_POSITION, FIXTURE_RATE), FIXTURE_RATE)

      withPlaying(scope, planner) { engine, recorder ->
        val before = recorder.seekCompletions.size
        engine.seekTo(MID_GOP_POSITION, SeekAccuracy.Exact)
        awaitContract("the seek to complete") { recorder.seekCompletions.size > before }
        engine.awaitReplacedPumps()

        recorder.seekCompletions.last().position shouldBe landing
        planner.opened.first().closed shouldBe true
        recorder.failures.shouldBeEmpty()
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        recorder.lastState.status shouldBe PlaybackStatus.Ready
      }
    }

  // Loading another edit stops the pump the last one was playing on, which is a stop of the engine's own.
  @Test
  fun `a stop mid-stream reports neither a failure nor an end`() =
    contractTest { scope ->
      val planner = ScriptedPlanner { ScriptedStream(it, frames = Long.MAX_VALUE, status = FAILED, stderr = STDERR) }

      withPlaying(scope, planner) { engine, recorder ->
        engine
          .awaitComposition(jvmTrimmedComposition(CLIP_LENGTH / 2))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the stopped pump to close") { planner.opened.first().closed }
        engine.awaitReplacedPumps()
        awaitContract("the new edit's first frame to land") { recorder.lastState.status == PlaybackStatus.Ready }

        recorder.failures.shouldBeEmpty()
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
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

  private suspend fun FfmpegPlayerEngine.awaitPumpsDone() {
    awaitStep("every pump to finish") { awaitIdle() }
  }

  private suspend fun FfmpegPlayerEngine.awaitReplacedPumps() {
    awaitStep("the replaced pumps to finish") { awaitRetired() }
  }

  private val ContractRecorder.failures: List<PlaybackEvent.Failed>
    get() = events.filterIsInstance<PlaybackEvent.Failed>()

  private companion object {
    // Few enough that a play-through ends well inside the budget.
    const val FRAMES = 6L

    // Any status but zero is a failure, and one is what ffmpeg exits with on a bad input.
    const val FAILED = 1

    const val STDERR = "scripted.mp4: No such file or directory"

    val PLAY_BUDGET = 10.seconds
  }
}
