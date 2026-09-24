package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settle
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
      val step = outputSlotTime(1, FIXTURE_RATE)

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

  // A seek between two frames presents the frame on the output slot it lands on, at that slot's own
  // time. One seek rounds down onto its slot and one rounds up, so neither a floor nor a ceil passes
  // both. The seek reports the position it was asked for once the frame is up, so the frame's own
  // time is looked for among every position the engine reported.
  @Test
  fun `a seek between two frames lands on the slot it rounds to`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val positions = mutableListOf<Duration>()

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val registration = subject.engine.addListener(PositionRecorder(positions))

        listOf(ROUNDS_DOWN, ROUNDS_UP).forEach { at ->
          val slot = outputSlotTime(outputSlotOf(at, FIXTURE_RATE), FIXTURE_RATE)
          subject.engine.seekTo(at, SeekAccuracy.Exact)
          awaitContract("the seek to $at to land on $slot") { slot in positions }
        }
        registration.cancel()
      }
    }

  // Every frame the pump hands over while playing sits on its own slot, a slot after the one before
  // it, rather than a step's worth of time past it. The ticker reads the playhead between frames as
  // well, so a frame's time repeats until the next one is up.
  @Test
  fun `playing presents every frame at its own slot's time`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val positions = mutableListOf<Duration>()

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }
        val registration = subject.engine.addListener(PositionRecorder(positions))

        subject.engine.play()
        awaitContract("playback to reach the end", timeout = END_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        registration.cancel()
      }

      val reported = positions.filter { it < CLIP_LENGTH }
      val presented = reported.filterIndexed { index, at -> index == 0 || reported[index - 1] != at }
      val slots = presented.map { outputSlotOf(it, FIXTURE_RATE) }
      presented.zip(slots).forEach { (position, slot) -> position shouldBe outputSlotTime(slot, FIXTURE_RATE) }
      slots.zipWithNext().forEach { (earlier, later) -> later shouldBe earlier + 1 }
      slots.last() shouldBe outputSlotOf(CLIP_LENGTH, FIXTURE_RATE) - 1

      // A count rather than a value: two distinct frames landing on the same slot would still read
      // as a consecutive, correctly-ending run once deduped, so only the count of frames the window
      // actually carried catches one going missing from it.
      val window = outputSlotsOf(TimeRange.of(Duration.ZERO, CLIP_LENGTH), FIXTURE_RATE)
      presented.size shouldBe window.count()
    }

  // A composition ending part way through a frame period still carries a frame for the slot that
  // period rounds to, so a seek past the end lands on that slot rather than the one before it. The
  // frame's own time is looked for among every position reported, as for a seek between two frames.
  @Test
  fun `a seek past the last frame lands on the last slot the file carries`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val last = outputSlotTime(outputSlotOf(ROUNDS_UP_END, FIXTURE_RATE) - 1, FIXTURE_RATE)
      val positions = mutableListOf<Duration>()

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmTrimmedComposition(ROUNDS_UP_END))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val registration = subject.engine.addListener(PositionRecorder(positions))

        subject.engine.seekTo(ROUNDS_UP_END, SeekAccuracy.Exact)
        awaitContract("the seek past the end to land on $last") { last in positions }
        registration.cancel()
      }
    }

  // A composition the pump reads forward from its head reports the frame it stopped on at that
  // frame's slot, the same time a windowed pump would have opened on.
  @Test
  fun `a readback read forward reports the slot it rounds to`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmTwoClipComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        listOf(LATE_ROUNDS_DOWN, LATE_ROUNDS_UP).forEach { at ->
          val frame = subject.engine.readback.await(at)
          frame.presentationTime shouldBe outputSlotTime(outputSlotOf(at, FIXTURE_RATE), FIXTURE_RATE)
        }
      }
    }

  private companion object {
    // The fixture runs 1.5s and the pump reads forward at about a millisecond a frame, so this is
    // real time plus a cold spawn rather than a decode budget.
    val END_BUDGET = 30.seconds

    // A third of the way into a frame period and three quarters of the way into one, early in the
    // clip and again late in it, well clear of the frame on screen when a readback asks.
    val ROUNDS_DOWN = 510.milliseconds
    val ROUNDS_UP = 525.milliseconds
    val LATE_ROUNDS_DOWN = 1_010.milliseconds
    val LATE_ROUNDS_UP = 1_025.milliseconds

    // A clip that ends three fifths of the way into a frame period, so its last slot is the one that
    // period rounds up to.
    val ROUNDS_UP_END = 1_020.milliseconds
  }
}

/**
 * Keeps every position an engine reports, in order.
 */
private class PositionRecorder(
  private val positions: MutableList<Duration>,
) : EngineListener {
  override fun onStateChanged(state: PlayerState) = Unit

  override fun onEvent(event: PlaybackEvent) = Unit

  override fun onPosition(position: Duration) {
    positions += position
  }

  override fun onPreviewInfo(info: PreviewInfo) = Unit
}

/**
 * Asks for the frame at [position] and suspends until it arrives, failing the test when it cannot be read.
 */
private suspend fun PreviewFrameReadback.await(position: Duration): ReadbackFrame =
  suspendCancellableCoroutine { continuation ->
    val handle =
      requestFrame(position) { result ->
        if (!continuation.isActive) return@requestFrame
        when (result) {
          is ReadbackResult.Success -> continuation.resume(result.frame)
          is ReadbackResult.Failure -> continuation.resume(fail("the readback failed: ${result.error}"))
        }
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }
