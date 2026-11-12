package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TimelineBursts
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Where an audio effect's kernel runs in an AVFoundation export, read off [TimelineBursts].
 *
 * The bursts sit on the composition timeline, so one heard where it belongs was made by a kernel handed the right
 * timeline position, and its level says whether the clip's own level ran after the effect or before it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAudioEffectMediaTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      addAudioEffectResolver(testAudioEffects)
    }
  private val source = MediaSource.of(fixture(TONE))

  // The second clip opens between two bursts, trimmed from past the source's start, so a kernel counting from the
  // clip or from the source would put its burst somewhere else.
  @Test
  fun aTrackEffectLaysItsBurstsOnTheTimelineAcrossTrimmedClips() =
    runTest(timeout = TIMEOUT) {
      val track =
        Track(
          listOf(
            Clip(source, TimeRange.of(200.milliseconds, 950.milliseconds)),
            Clip(source, TimeRange.of(1_000.milliseconds, 1_500.milliseconds)),
          ),
        ).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(Composition(listOf(track)))

      audio.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aClipEffectRunsAheadOfTheClipsLevel() =
    runTest(timeout = TIMEOUT) {
      val clip = clip(AudioLevel.Volume(QUARTER)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(Composition(listOf(Track(listOf(clip)))))

      audio.shouldHearBursts(0..2, LOUD * QUARTER)
    }

  // The effect writes past full scale and the clip's level brings it back under. A render that clamped the effect's
  // samples would hand the level a flattened tone, which reads about a fifth quieter.
  @Test
  fun aClipEffectPastFullScaleReachesTheClipsLevelWhole() =
    runTest(timeout = TIMEOUT) {
      val clip = clip(AudioLevel.Volume(QUARTER)).withAudioEffects(listOf(TimelineBursts(OVER)))

      val audio = exportedAudio(Composition(listOf(Track(listOf(clip)))))

      audio.shouldHearBursts(0..2, OVER * QUARTER)
    }

  @Test
  fun aFadeTakesTheEffectDownWithTheClip() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(source, TRIM, fadeIn = TimelineBursts.PERIOD * 2, audioEffects = listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(Composition(listOf(Track(listOf(clip)))))

      // The fade is a quarter of the way up at the middle of the first burst's reading and done by the third burst.
      withClue("the burst under the start of the fade") { audio.burst(0) shouldBeLessThan LOUD * QUARTER }
      audio.shouldHearBursts(2..2, LOUD)
    }

  @Test
  fun aCompositionEffectRunsOnTheMixAfterEveryLevel() =
    runTest(timeout = TIMEOUT) {
      val edit =
        Composition(listOf(Track(listOf(clip(AudioLevel.Volume(QUARTER))))))
          .withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD)
    }

  // A tap ahead of the time stretch would have its bursts stretched with the clip, twice as long and twice as far
  // apart.
  @Test
  fun aSlowedClipKeepsItsEffectOnTheTimeline() =
    runTest(timeout = TIMEOUT) {
      val clip =
        Clip(
          source,
          TimeRange.of(200.milliseconds, 700.milliseconds),
          speed = 0.5,
          audioEffects = listOf(TimelineBursts(LOUD)),
        )

      val audio = exportedAudio(Composition(listOf(Track(listOf(clip)))))

      audio.shouldHearBursts(0..2, LOUD)
    }

  // The clip's audio is rendered with its effects run over it and their latency taken out, so each burst opens on
  // the frame it opens on without the delay.
  @Test
  fun aClipEffectWithLatencyKeepsItsBurstsOnTheirFrames() =
    runTest(timeout = TIMEOUT) {
      val plain = Clip(source, TRIM, audioEffects = listOf(TimelineBursts(LOUD)))
      val delayed = Clip(source, TRIM, audioEffects = listOf(TimelineBursts(LOUD), Delay(LATENCY_FRAMES)))

      val expected = exportedAudio(Composition(listOf(Track(listOf(plain)))))
      val audio = exportedAudio(Composition(listOf(Track(listOf(delayed)))))

      audio.shouldHearBursts(0..2, LOUD)
      audio.samples.size shouldBe expected.samples.size
      audio.onsets() shouldBe expected.onsets()
    }

  // The delay hands the source's own sound on and its host takes the delay out, so the rendered clip holds what the
  // clip lays with no effect at all, sample for sample. A render that opened off the trim would move the tone.
  @Test
  fun aClipWithAnEffectKeepsItsSourcesSoundOnItsFrames() =
    runTest(timeout = TIMEOUT) {
      val plain = Clip(source, TRIM)
      val delayed = Clip(source, TRIM, audioEffects = listOf(Delay(LATENCY_FRAMES)))

      val expected = exportedAudio(Composition(listOf(Track(listOf(plain))))).samples
      val audio = exportedAudio(Composition(listOf(Track(listOf(delayed))))).samples

      audio.size shouldBe expected.size
      expected.indices.maxOf { abs(expected[it] - audio[it]) } shouldBeLessThan SAME_SOUND
    }

  // The bed's second pass opens between two bursts and its third on the last one. A pass rendered from the frame
  // another pass opens on would put its bursts somewhere else.
  @Test
  fun aLoopingTracksEffectLaysItsBurstsOnEveryPass() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(MediaSource.of(fixture(PICTURE)), TRIM)), content = TrackContent.Video)
      val bed =
        Track(
          listOf(Clip(source, TimeRange.of(200.milliseconds, 700.milliseconds))),
          content = TrackContent.Audio,
          looping = true,
          audioEffects = listOf(TimelineBursts(LOUD)),
        )

      val audio = exportedAudio(Composition(listOf(picture, bed)))

      audio.shouldHearBursts(0..2, LOUD)
    }

  // The delay is taken out again by its host, so the clip's bursts come through the mix effect where they were laid
  // and as loud as the clip's level left them.
  @Test
  fun aClipEffectIsHeardThroughAMixEffectWithLatency() =
    runTest(timeout = TIMEOUT) {
      val clip = clip(AudioLevel.Volume(QUARTER)).withAudioEffects(listOf(TimelineBursts(LOUD)))
      val edit = Composition(listOf(Track(listOf(clip)))).withAudioEffects(listOf(Delay(LATENCY_FRAMES)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD * QUARTER)
    }

  @Test
  fun aMixEffectWithLatencyKeepsTheMixItsLength() =
    runTest(timeout = TIMEOUT) {
      val track = Track(listOf(Clip(source, TRIM)))
      val plain = Composition(listOf(track)).withAudioEffects(listOf(TimelineBursts(LOUD)))
      val delayed = plain.withAudioEffects(listOf(TimelineBursts(LOUD), Delay(LATENCY_FRAMES)))

      val audio = exportedAudio(delayed)

      audio.shouldHearBursts(0..2, LOUD)
      audio.samples.size shouldBe exportedAudio(plain).samples.size
    }

  // The bed ends a third of the way in and the picture track lays no audio, so most of the timeline has no audio
  // track under it. The composition's effect runs over all of it.
  @Test
  fun aCompositionEffectRunsPastTheEndOfTheLastAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(MediaSource.of(fixture(PICTURE)), TRIM)), content = TrackContent.Video)
      val bed = Track(listOf(Clip(source, TimeRange.of(200.milliseconds, 500.milliseconds))), TrackContent.Audio)
      val edit = Composition(listOf(picture, bed)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aCompositionEffectRunsOverAnEditWithNoAudioTrackAtAll() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(MediaSource.of(fixture(PICTURE)), TRIM)), content = TrackContent.Video)
      val edit = Composition(listOf(picture)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aClipEffectThatThrowsFailsTheExportAndLeavesNoFile() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(source, TRIM, audioEffects = listOf(Broken))

      val (finished, output) = exported(Composition(listOf(Track(listOf(clip)))))

      finished.shouldBeInstanceOf<ExportStatus.Failure>().error.message shouldContain THROWN
      NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
    }

  @Test
  fun aCompositionEffectThatThrowsFailsTheExportAndLeavesNoFile() =
    runTest(timeout = TIMEOUT) {
      val edit = Composition(listOf(Track(listOf(Clip(source, TRIM))))).withAudioEffects(listOf(Broken))

      val (finished, output) = exported(edit)

      finished.shouldBeInstanceOf<ExportStatus.Failure>().error.message shouldContain THROWN
      NSFileManager.defaultManager.fileExistsAtPath(output) shouldBe false
    }

  private fun clip(level: AudioLevel): Clip = Clip(source, TRIM, audio = level)

  // The frame each run of sound opens on, a run being closed by a stretch of silence.
  private fun AudioProbe.onsets(): List<Int> {
    val onsets = mutableListOf<Int>()
    var quiet = QUIET_RUN
    samples.forEachIndexed { index, sample ->
      if (abs(sample) > AUDIBLE) {
        if (quiet >= QUIET_RUN) onsets += index
        quiet = 0
      } else {
        quiet++
      }
    }
    return onsets
  }

  private suspend fun exportedAudio(edit: Composition): AudioProbe {
    val (finished, output) = exported(edit)
    if (finished is ExportStatus.Failure) error(finished.error.message)
    return audioOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
  }

  private suspend fun exported(edit: Composition): Pair<ExportStatus, String> =
    withContext(Dispatchers.Default) {
      val output = NSTemporaryDirectory() + "filmstrip-apple-audio-effect-" + NSUUID().UUIDString + ".mp4"
      filmstrip.export(edit, ExportSpec(), MediaSink.of(output)).toList().last() to output
    }

  private companion object {
    val TIMEOUT = 3.minutes
    const val TONE = "tone_bed_880.mp4"
    const val PICTURE = "apple_export_a.mp4"
    val TRIM: TimeRange = TimeRange.of(200.milliseconds, 1_300.milliseconds)
    const val LOUD = 0.5f
    const val QUARTER = 0.25f
    const val OVER = 1.5f

    // A hundredth of what the tone moves by when it lands one frame off.
    const val SAME_SOUND = 1e-4f
    const val LATENCY_FRAMES = 480
    const val AUDIBLE = 0.05f
    const val QUIET_RUN = 1_000
  }
}
