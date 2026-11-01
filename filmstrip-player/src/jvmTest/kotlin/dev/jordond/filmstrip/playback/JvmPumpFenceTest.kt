package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitStep
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.playback.internal.loopWrapOf
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [FfmpegPlayerEngine.awaitQuiet] waits for while playback is paused.
 *
 * A paused seek opens a pump that shows the frame it lands on and then parks until playback is wanted, so it never
 * finishes. The fence has to return once it parks and not before it lands, and has to follow a seek handed over while
 * it waits onto the pump that seek opens.
 */
class JvmPumpFenceTest {
  @Test
  fun `a paused seek lands before the engine goes quiet`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        for (position in PROBE_POSITIONS) {
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(position, SeekAccuracy.Exact)
          // All a check without the fence would see, since the pump has not run yet.
          recorder.seekCompletions.size shouldBe before

          awaitStep("the pump to park") { subject.engine.awaitQuiet() }
          recorder.seekCompletions.size shouldBe before + 1
        }
      }
    }

  // The exact seek holds the relaxed one back until it lands, and the relaxed one then waits out the scrub debounce
  // before it opens its pump. The pump the first seek opened has parked long before then.
  @Test
  fun `a relaxed seek a landing hands over lands before the engine goes quiet`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        val before = recorder.seekCompletions.size
        subject.engine.seekTo(PROBE_POSITIONS.first(), SeekAccuracy.Exact)
        subject.engine.seekTo(PROBE_POSITIONS.last(), SeekAccuracy.Nearest)
        awaitStep("both pumps to park") { subject.engine.awaitQuiet() }

        recorder.seekCompletions.drop(before).map { it.position } shouldBe PROBE_POSITIONS
        recorder.playhead shouldBe PROBE_POSITIONS.last()
      }
    }

  // Held in a scrub, so the wrap is a relaxed seek that waits out the scrub debounce before it opens its pump. The pump
  // that wrapped has finished long before then, and playback is paused as soon as the lap is reported, so the wrap's
  // pump parks on the frame it lands on.
  @Test
  fun `a wrap handed over while playing lands before the engine goes quiet`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val range = TimeRange.from(LOOP_START)
      withEngine(subject.engine) { recorder ->
        subject.engine
          .awaitComposition(jvmFixtureComposition(), startAt = LOOP_START)
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val wrap = loopWrapOf(range, duration, FIXTURE_RATE) ?: fail("$range has nothing to loop")

        subject.engine.setLoopRange(range)
        subject.engine.beginScrub()
        val before = recorder.seekCompletions.size
        subject.engine.play()
        // A pump still parked from before play would read as quiet already.
        awaitContract("playback to move on") { recorder.playhead?.let { it > LOOP_START } == true }
        coroutineScope {
          launch {
            awaitContract("$range to wrap") { recorder.events.any { it is PlaybackEvent.RangeLooped } }
            subject.engine.pause()
          }
          awaitStep("the wrap's pump to park") { subject.engine.awaitQuiet() }
        }

        recorder.seekCompletions.drop(before).map { it.position } shouldBe
          listOf(outputSlotTime(wrap.firstSlot, FIXTURE_RATE))
      }
    }

  private companion object {
    // Half a second before the fixture's end, so a pass is short.
    val LOOP_START = 1_000.milliseconds
  }
}
