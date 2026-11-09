package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.transform.internal.heldTail
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seeks the desktop engine takes while it plays, each landing on a slot and then playing on to the end.
 *
 * The pump a seek opens can run out soon after, near the end of a composition or when its process fails, and the
 * seek still completes on its own slot before the end or the failure is reported.
 */
class JvmSeekWhilePlayingMediaTest {
  // Under an audio track that outlasts the video, the last slot holds the video's last frame like every other slot of
  // the held tail. A seek to the end while playing shows that frame and lands on its slot rather than on the end,
  // which no frame sits on, and playback then ends at the end.
  @Test
  fun `a seek to the end while playing lands on the last slot`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val composition = jvmAudioTailComposition()
      val tail = heldTailIn(composition)
      withClue("the audio outlasts the video, yet the composition holds no tail") { (!tail.isEmpty()).shouldBeTrue() }
      val last = outputSlotTime(tail.last, FIXTURE_RATE)

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")

        subject.engine.play()
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(duration, SeekAccuracy.Exact)
        awaitContract("playback to reach the end", timeout = END_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        awaitContract("the seek to the end to complete") { recorder.seekCompletions.size > before }

        recorder.seekCompletions.last().position shouldBe last
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .single()
          .finalPosition shouldBe duration
        recorder.playhead shouldBe duration
      }
    }

  // Every slot of the held tail carries the video's last frame, so a seek into the middle of it while playing lands on
  // its own slot. Playback then goes on from there through every slot the tail has left and ends at the end.
  @Test
  fun `a seek into the held tail while playing lands on its own slot and plays the tail out`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val composition = jvmAudioTailComposition()
      val tail = heldTailIn(composition)
      val slot = outputSlotOf(TAIL_SEEK, FIXTURE_RATE)
      val landing = outputSlotTime(slot, FIXTURE_RATE)
      val positions = mutableListOf<Duration>()
      withClue("$TAIL_SEEK lands on slot $slot, not inside the tail $tail") {
        (slot > tail.first && slot < tail.last).shouldBeTrue()
      }

      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        val duration = recorder.lastState.duration ?: fail("the engine reported no duration")
        val registration = subject.engine.addListener(PositionRecorder(positions))

        subject.engine.play()
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(TAIL_SEEK, SeekAccuracy.Exact)
        awaitContract("playback to reach the end", timeout = END_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Ended>().isNotEmpty()
        }
        awaitContract("the seek into the tail to complete") { recorder.seekCompletions.size > before }
        registration.cancel()

        recorder.seekCompletions.last().position shouldBe landing
        // The ticker reads the playhead between frames as well, so a slot repeats until the next one is up.
        val played = slotsPresented(positions.dropWhile { it != landing }, duration)
        played.filterIndexed { index, at -> index == 0 || played[index - 1] != at } shouldBe (slot..tail.last).toList()
        recorder.events
          .filterIsInstance<PlaybackEvent.Ended>()
          .single()
          .finalPosition shouldBe duration
      }
    }

  // The seek's process fails because the source has gone from disk since the composition loaded. The seek still
  // completes, on the slot it asked for since nothing moved the playhead off it, and only then is the failure reported.
  @Test
  fun `a seek whose process fails while playing lands on its own slot and fails`() =
    contractTest { scope ->
      val subject = JvmEngineUnderTest(scope)
      val source = File.createTempFile("filmstrip-vanishing", ".mp4").also { it.deleteOnExit() }
      File(jvmFixtureClip()).copyTo(source, overwrite = true)
      val landing = outputSlotTime(outputSlotOf(VANISHED_SEEK, FIXTURE_RATE), FIXTURE_RATE)

      withEngine(subject.engine) { recorder ->
        val loaded = subject.engine.awaitComposition(jvmVideoCompositionOf(source))
        loaded.shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the first frame to land") { recorder.playhead != null }
        subject.engine.play()
        // The pump already running holds the file open, so only the one the seek spawns finds it gone.
        source.delete() shouldBe true
        val before = recorder.seekCompletions.size
        subject.engine.seekTo(VANISHED_SEEK, SeekAccuracy.Exact)
        awaitContract("playback to fail", timeout = END_BUDGET) {
          recorder.events.filterIsInstance<PlaybackEvent.Failed>().isNotEmpty()
        }
        awaitContract("the seek to complete") { recorder.seekCompletions.size > before }

        recorder.seekCompletions.last().position shouldBe landing
        recorder.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
        val failed = recorder.events.filterIsInstance<PlaybackEvent.Failed>().single()
        failed.error.message shouldContain source.name
        recorder.lastState.status shouldBe PlaybackStatus.Error(failed.error)
        recorder.lastState.playWhenReady shouldBe true
        recorder.playhead shouldBe landing
      }
    }

  private companion object {
    // The fixture runs 1.5s and the pump reads forward at about a millisecond a frame, so this is
    // real time plus a cold spawn rather than a decode budget.
    val END_BUDGET = 30.seconds

    // Inside the audio tail composition's held tail and clear of both its ends, part way into a frame period.
    val TAIL_SEEK = 1_210.milliseconds

    // Well inside the clip and a third of the way into a frame period.
    val VANISHED_SEEK = 510.milliseconds
  }
}

/**
 * The fixture's video cut short of its audio, so the composition holds the video's last frame over the rest.
 *
 * The audio runs the whole [CLIP_LENGTH] and the video stops a third of the way short of it.
 */
private fun jvmAudioTailComposition(): Composition {
  val source = MediaSource.of(jvmFixtureClip())
  return Composition(
    tracks =
      listOf(
        Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH * 2 / 3)))),
        Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH))), content = TrackContent.Audio),
      ),
  )
}

/**
 * The video of [file] over [CLIP_LENGTH], on a track that drops its audio so the preview runs the filter graph.
 */
private fun jvmVideoCompositionOf(file: File): Composition =
  Composition(
    tracks =
      listOf(
        Track(
          listOf(Clip(MediaSource.of(file.absolutePath), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
          content = TrackContent.Video,
        ),
      ),
  )

/**
 * The output slots [composition] holds its video's last frame over, read off the lowering the engine plays.
 *
 * Fails the test when the preview refuses the composition.
 */
private suspend fun heldTailIn(composition: Composition): LongRange =
  when (
    val result =
      FfmpegPreviewPlanner(
        CONTRACT_COMPONENTS,
      ).plan(composition, PlayerConfig().qualityPolicy, ExportSpec())
  ) {
    is PlanResult.Ready -> result.plan.resolved.heldTail
    is PlanResult.Refused -> fail("the preview refused the composition: ${result.error}")
  }
