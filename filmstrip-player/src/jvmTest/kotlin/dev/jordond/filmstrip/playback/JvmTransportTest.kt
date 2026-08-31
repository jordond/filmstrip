package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The transport this backend has to build for itself, since ffmpeg answers none of it.
 *
 * Playing to the end is the reader running out rather than a player reporting an event, and a frame
 * step is a respawn rather than a decoder nudged forward, so both are worth pinning where a change
 * to the pump would break them.
 */
class JvmTransportTest {
  @Test
  fun `playing to the end reports Ended once and leaves playback unwanted`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }

        subject.engine.play()
        awaitContract("playback to reach the end", timeout = END_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        settle()

        recorder.events.filterIsInstance<PlaybackEvent.Ended>().size shouldBe 1
        recorder.lastState.playWhenReady shouldBe false
        recorder.lastState.status shouldBe PlaybackStatus.Ended
      }
    }

  // FrameStepping is claimed, so the step has to actually land on the next frame rather than
  // somewhere near it.
  @Test
  fun `a frame step moves the playhead by exactly one frame`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val step = 1.seconds / 30

      withEngine(subject.engine) { recorder ->
        subject.engine.features.supports(PlayerFeature.FrameStepping) shouldBe true
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        recorder.playhead shouldBe Duration.ZERO

        subject.engine.stepFrames(1)
        awaitContract("the step forward to land") { recorder.playhead == step }

        subject.engine.stepFrames(-1)
        awaitContract("the step back to land") { recorder.playhead == Duration.ZERO }

        // Stepping off the front is refused rather than clamped, so the playhead does not move.
        subject.engine.stepFrames(-1)
        settle()
        recorder.playhead shouldBe Duration.ZERO
      }
    }

  private companion object {
    // The fixture runs 1.5s and the pump reads forward at about a millisecond a frame, so this is
    // real time plus a cold spawn rather than a decode budget.
    val END_BUDGET = 30.seconds
  }
}
