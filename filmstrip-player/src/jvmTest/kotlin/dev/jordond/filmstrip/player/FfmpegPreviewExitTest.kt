package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.loopWrapOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the desktop engine answers a real ffmpeg preview that runs out on its own.
 *
 * The engine reads the process's exit status before it ends or wraps, so a clean exit ends playback or wraps the loop
 * range rather than failing.
 */
class FfmpegPreviewExitTest {
  @Test
  fun `a preview that runs out cleanly ends`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        subject.engine.play()
        awaitContract("playback to end", timeout = PLAY_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }

        recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldBeEmpty()
        recorder.lastState.status shouldBe PlaybackStatus.Ended
      }
    }

  @Test
  fun `a loop range the preview ends inside wraps`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val range = TimeRange.from(LOOP_START)
        val wrap = loopWrapOf(range, duration, FIXTURE_RATE) ?: fail("$range has nothing to loop")
        wrap.reachesEnd shouldBe true

        subject.engine.setLoopRange(range)
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(range.start, SeekAccuracy.Exact)
        awaitContract("the seek to ${range.start} to complete") { recorder.seekCompletions.size > before }
        subject.engine.play()
        awaitContract("$range to wrap", timeout = PLAY_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().isNotEmpty()
        }
        subject.engine.pause()

        recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldBeEmpty()
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
      }
    }

  private companion object {
    // Real time for the fixture plus a cold spawn or two.
    val PLAY_BUDGET = 30.seconds

    // Most of the way through the fixture, so a pass is short.
    val LOOP_START = 1_000.milliseconds
  }
}
