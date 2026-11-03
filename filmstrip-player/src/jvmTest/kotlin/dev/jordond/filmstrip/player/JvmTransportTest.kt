package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
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
        awaitStep("every pump to finish") { subject.engine.awaitIdle() }

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
        awaitStep("the engine to go quiet") { subject.engine.awaitQuiet() }
        recorder.playhead shouldBe Duration.ZERO
      }
    }

  // A seek between two frames presents the frame on the output slot it lands on, and its completion
  // and the playhead both carry that slot's own time. One seek rounds down onto its slot and one
  // rounds up, so neither a floor nor a ceil passes both.
  @Test
  fun `a seek between two frames lands on the slot it rounds to`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        listOf(ROUNDS_DOWN, ROUNDS_UP).forEach { at ->
          val slot = outputSlotTime(outputSlotOf(at, FIXTURE_RATE), FIXTURE_RATE)
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Exact)
          awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }

          recorder.seekCompletions.last().position shouldBe slot
          recorder.playhead shouldBe slot
        }
      }
    }

  // A relaxed seek waits out the scrub debounce and then settles exactly, so it lands on the same
  // slot an exact seek would and the readback finds that frame at that time. One rounds down onto
  // its slot and one rounds up, both mid GOP, where a sync sample would sit slots away.
  @Test
  fun `a relaxed seek reports the slot it settled on`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val third = outputSlotTime(1, FIXTURE_RATE) / 3

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(jvmFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        listOf(MID_GOP_POSITION + third, MID_GOP_POSITION + third * 2).forEach { at ->
          val landing = outputSlotTime(outputSlotOf(at, FIXTURE_RATE), FIXTURE_RATE)
          val before = recorder.seekCompletions.size
          subject.engine.seekTo(at, SeekAccuracy.Nearest)
          awaitContract("the relaxed seek to $at to complete") { recorder.seekCompletions.size > before }

          recorder.seekCompletions.last().position shouldBe landing
          subject.engine.readback
            .await(landing)
            .presentationTime shouldBe landing
        }
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
  // period rounds to, so a seek past the end lands on that slot rather than the one before it, and
  // its completion says so.
  @Test
  fun `a seek past the last frame lands on the last slot the file carries`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val last = outputSlotTime(outputSlotOf(ROUNDS_UP_END, FIXTURE_RATE) - 1, FIXTURE_RATE)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmTrimmedComposition(ROUNDS_UP_END))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }

        val before = recorder.seekCompletions.size
        subject.engine.seekTo(ROUNDS_UP_END, SeekAccuracy.Exact)
        awaitContract("the seek past the end to complete") { recorder.seekCompletions.size > before }

        recorder.seekCompletions.last().position shouldBe last
        recorder.playhead shouldBe last
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

  // A composition ending part way into a frame period has no frame on the slot that period opens on, and a play that
  // ran out leaves the playhead on the end, past its last slot. Playing again still presents a frame, and every frame
  // either play presents sits on a slot the composition carries.
  @Test
  fun `playing again after the end presents only slots the composition carries`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(ROUNDS_DOWN_END))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val carried = outputSlotsOf(TimeRange.of(Duration.ZERO, duration), FIXTURE_RATE)

        val (played, replayed) = playTwiceToTheEnd(subject.engine, recorder)

        slotsPresented(played + replayed, duration).filterNot { it in carried }.shouldBeEmpty()
        slotsPresented(replayed, duration).shouldNotBeEmpty()
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .last()
          .finalPosition shouldBe duration
      }
    }

  // A playback range ending part way into a frame period stops the same way, on the slot before the one its end rounds
  // to, and playing again presents nothing past the range either.
  @Test
  fun `playing again after a playback range ends presents only slots the range carries`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val range = TimeRange.of(Duration.ZERO, ROUNDS_UP_END)
      val carried = outputSlotsOf(range, FIXTURE_RATE)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }
        subject.engine.setPlaybackRange(range)

        val (played, replayed) = playTwiceToTheEnd(subject.engine, recorder)

        slotsPresented(played + replayed, ROUNDS_UP_END).filterNot { it in carried }.shouldBeEmpty()
        slotsPresented(replayed, ROUNDS_UP_END).shouldNotBeEmpty()
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .last()
          .finalPosition shouldBe ROUNDS_UP_END
      }
    }

  // A playback range inside the clip plays the slots it carries and stops on the last of them. One range ends three
  // tenths of the way into a frame period, so the slot that period opens on sits before its end and is still not one
  // of its slots, and the other ends three fifths of the way in, where that slot is its last.
  @Test
  fun `a playback range plays exactly the slots it carries`() =
    contractTest { scope ->
      listOf(TimeRange.of(ROUNDS_DOWN, ROUNDS_DOWN_END), TimeRange.of(ROUNDS_UP, ROUNDS_UP_END)).forEach { range ->
        val subject = JvmEngineUnderTest(scope)
        val positions = mutableListOf<Duration>()

        withEngine(subject.engine) { recorder ->
          val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
          loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the first frame to land") { recorder.playhead != null }
          val registration = subject.engine.addListener(PositionRecorder(positions))

          subject.engine.setPlaybackRange(range)
          awaitContract("the seek to $range's start to complete") { recorder.seekCompletions.isNotEmpty() }
          subject.engine.play()
          awaitContract("playback to reach the end of $range", timeout = END_BUDGET) {
            recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
          }
          registration.cancel()
        }

        val played = slotsPresented(positions, checkNotNull(range.endExclusive)).distinct()
        played shouldBe outputSlotsOf(range, FIXTURE_RATE).toList()
      }
    }

  // A playback range narrower than one frame period, whose end rounds down onto the same slot its start does, covers
  // no slot of its own. Replaying it must still land on that one slot rather than the slot before it.
  @Test
  fun `playing again after a sub-frame playback range ends never lands before its start`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val range = TimeRange.of(SUB_FRAME_START, SUB_FRAME_END)
      val carried = setOf(outputSlotOf(range.start, FIXTURE_RATE))

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoComposition(CLIP_LENGTH))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to be ready") { recorder.lastState.hasComposition }
        subject.engine.setPlaybackRange(range)

        val (played, replayed) = playTwiceToTheEnd(subject.engine, recorder)

        slotsPresented(played + replayed, SUB_FRAME_END).filterNot { it in carried }.shouldBeEmpty()
        slotsPresented(replayed, SUB_FRAME_END).shouldNotBeEmpty()
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .last()
          .finalPosition shouldBe SUB_FRAME_END
      }
    }

  /**
   * Plays [engine] to the end, then plays it again to the end, and returns the positions each play reported.
   */
  private suspend fun playTwiceToTheEnd(
    engine: FfmpegPlayerEngine,
    recorder: ContractRecorder,
  ): Pair<List<Duration>, List<Duration>> {
    val positions = mutableListOf<Duration>()
    val registration = engine.addListener(PositionRecorder(positions))

    engine.play()
    awaitContract("playback to reach the end", timeout = END_BUDGET) {
      recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
    }
    awaitStep("every pump to finish") { engine.awaitIdle() }
    val played = positions.toList()

    engine.play()
    awaitContract("playing again to reach the end", timeout = END_BUDGET) {
      recorder.events.filterIsInstance<PlaybackEvent.Ended>().size > 1
    }
    registration.cancel()
    return played to positions.drop(played.size)
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

    // A clip or a range that ends three fifths of the way into a frame period, so its last slot is the
    // one that period rounds up to.
    val ROUNDS_UP_END = 1_020.milliseconds

    // A clip or a range that ends three tenths of the way into a frame period, so the slot that period
    // opens on is not one it carries.
    val ROUNDS_DOWN_END = 1_010.milliseconds

    // A playback range narrower than one frame period, whose end rounds down onto the same slot its
    // start does, so together the two cover no slot of their own.
    val SUB_FRAME_START = 510.milliseconds
    val SUB_FRAME_END = 515.milliseconds
  }
}

/**
 * The fixture's video trimmed to [length], on a track that drops its audio.
 *
 * A track that drops a stream is not a copy of the source, so the preview runs the filter graph and opens its pump with
 * an input seek to the slot it is asked for.
 */
internal fun jvmVideoComposition(length: Duration): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(Clip(MediaSource.of(jvmFixtureClip()), TimeRange.of(Duration.ZERO, length))),
          content = TrackContent.Video,
        ),
      ),
  )

/**
 * The output slots of the frames among [positions], leaving out [end], where a finished play leaves the playhead
 * with no frame on it.
 *
 * Fails the test when a frame sits between two slots rather than on one.
 */
internal fun slotsPresented(
  positions: List<Duration>,
  end: Duration,
): List<Long> =
  positions
    .filter { it != end }
    .map { at -> outputSlotOf(at, FIXTURE_RATE).also { at shouldBe outputSlotTime(it, FIXTURE_RATE) } }

/**
 * Keeps every position an engine reports, in order.
 */
internal class PositionRecorder(
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
