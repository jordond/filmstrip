package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.loopWrapOf
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How soon the desktop engine answers a real ffmpeg preview that runs out on its own.
 *
 * The engine reads the process's exit status before it ends or wraps, so each test times the gap between the last slot
 * landing on screen and the end or wrap after it. A process that exited clean has its status to hand, so the gap stays
 * well inside [PreviewStream.EXIT_GRACE], the longest a status read waits on a process still running.
 */
class FfmpegPreviewExitTest {
  @Test
  fun `a preview that runs out cleanly ends without waiting on its process`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val timing = EndTiming(lastSlot = outputSlotsOf(TimeRange.of(Duration.ZERO, duration), FIXTURE_RATE).last)
        val registration = subject.engine.addListener(timing)

        subject.engine.play()
        awaitContract("playback to end", timeout = PLAY_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        registration.cancel()

        recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldBeEmpty()
        recorder.lastState.status shouldBe PlaybackStatus.Ended
        timing.assertPrompt()
      }
    }

  @Test
  fun `a loop range the preview ends inside wraps without waiting on its process`() =
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
        val timing = EndTiming(lastSlot = wrap.lastSlot)

        subject.engine.setLoopRange(range)
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(range.start, SeekAccuracy.Exact)
        awaitContract("the seek to ${range.start} to complete") { recorder.seekCompletions.size > before }
        val registration = subject.engine.addListener(timing)
        subject.engine.play()
        awaitContract("$range to wrap", timeout = PLAY_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.RangeLooped>().isNotEmpty()
        }
        subject.engine.pause()
        registration.cancel()

        recorder.events.filterIsInstance<PlaybackEvent.Failed>().shouldBeEmpty()
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        timing.assertPrompt()
      }
    }

  private companion object {
    // Real time for the fixture plus a cold spawn or two.
    val PLAY_BUDGET = 30.seconds

    // Most of the way through the fixture, so a pass is short.
    val LOOP_START = 1_000.milliseconds

    // Half the grace leaves room for a loaded machine while a gap that waited it out still fails.
    val PROMPT = PreviewStream.EXIT_GRACE / 2
  }

  /**
   * Marks when the frame on [lastSlot] first lands and when the first end or wrap after it is reported.
   */
  private class EndTiming(
    private val lastSlot: Long,
  ) : EngineListener {
    @Volatile
    private var shown: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile
    private var ended: TimeSource.Monotonic.ValueTimeMark? = null

    override fun onStateChanged(state: PlayerState) = Unit

    override fun onEvent(event: PlaybackEvent) {
      if (ended != null || shown == null) return
      if (event is PlaybackEvent.Ended || event is PlaybackEvent.RangeLooped) ended = TimeSource.Monotonic.markNow()
    }

    override fun onPosition(position: Duration) {
      if (shown == null && outputSlotOf(position, FIXTURE_RATE) == lastSlot) shown = TimeSource.Monotonic.markNow()
    }

    override fun onPreviewInfo(info: PreviewInfo) = Unit

    fun assertPrompt() {
      val shownAt = shown ?: fail("slot $lastSlot never landed")
      val endedAt = ended ?: fail("nothing ended or wrapped after slot $lastSlot")
      val gap = endedAt - shownAt
      assertTrue(gap < PROMPT, "the end came $gap after slot $lastSlot, not inside $PROMPT")
    }
  }
}
