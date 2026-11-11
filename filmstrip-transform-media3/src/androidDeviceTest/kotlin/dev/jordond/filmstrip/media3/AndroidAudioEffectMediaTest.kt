package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.test.TimelineBursts
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Where an audio effect's kernel runs in a media3 export, read off [TimelineBursts].
 *
 * The bursts sit on the composition timeline, so one heard where it belongs was made by a kernel handed the right
 * timeline position, and its level says whether the clip's own level ran after the effect or before it.
 */
class AndroidAudioEffectMediaTest {
  private val probe =
    Media3ExportProbe {
      addAudioEffectResolver(
        AudioEffectResolver { spec, _ ->
          (spec as? TimelineBursts)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(it::newKernel)) }
        },
      )
    }

  // The second clip opens between two bursts, trimmed from past the source's start, so a kernel counting from the
  // clip or from the source would put its burst somewhere else.
  @Test
  fun aTrackEffectLaysItsBurstsOnTheTimelineAcrossTrimmedClips() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
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

  @Test
  fun aFadeTakesTheEffectDownWithTheClip() =
    runTest(timeout = TIMEOUT) {
      val clip =
        Clip(probe.fixture(), TRIM, fadeIn = TimelineBursts.PERIOD * 2, audioEffects = listOf(TimelineBursts(LOUD)))

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

  @Test
  fun aSlowedClipKeepsItsEffectOnTheTimeline() =
    runTest(timeout = TIMEOUT) {
      val clip =
        Clip(
          probe.fixture(),
          TimeRange.of(200.milliseconds, 700.milliseconds),
          speed = 0.5,
          audioEffects = listOf(TimelineBursts(LOUD)),
        )

      val audio = exportedAudio(Composition(listOf(Track(listOf(clip)))))

      audio.shouldHearBursts(0..2, LOUD)
    }

  // The bed ends a third of the way in and the picture track lays no audio, so most of the timeline has no audio
  // under it. The composition's effect runs over all of it.
  @Test
  fun aCompositionEffectRunsPastTheEndOfTheLastAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(probe.fixture(), TRIM)), content = TrackContent.Video)
      val bed =
        Track(listOf(Clip(probe.fixture(), TimeRange.of(200.milliseconds, 500.milliseconds))), TrackContent.Audio)
      val edit = Composition(listOf(picture, bed)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aCompositionEffectRunsOverAnEditWithNoAudioTrackAtAll() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(probe.fixture(), TRIM)), content = TrackContent.Video)
      val edit = Composition(listOf(picture)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val audio = exportedAudio(edit)

      audio.shouldHearBursts(0..2, LOUD)
    }

  private fun clip(level: AudioLevel): Clip = Clip(probe.fixture(), TRIM, audio = level)

  private suspend fun exportedAudio(edit: Composition): DecodedAudio {
    val file = probe.export(edit, ExportSpec())
    return decodeAudio(file).also { file.delete() }
  }

  // The tone's level over the middle of burst [index], clear of the edges an encoder smears.
  private fun DecodedAudio.burst(index: Int): Float =
    amplitudeOver(TimelineBursts.burstStart(index) + EDGE, TimelineBursts.LENGTH - EDGE * 2, TimelineBursts.TONE_HZ)

  // The tone's level over the middle of the silence after burst [index].
  private fun DecodedAudio.gap(index: Int): Float =
    amplitudeOver(
      TimelineBursts.burstStart(index) + TimelineBursts.LENGTH + EDGE,
      TimelineBursts.PERIOD - TimelineBursts.LENGTH - EDGE * 2,
      TimelineBursts.TONE_HZ,
    )

  private fun DecodedAudio.shouldHearBursts(
    bursts: IntRange,
    level: Float,
  ) {
    bursts.forEach { index ->
      withClue("burst $index, opening at ${TimelineBursts.burstStart(index)}") {
        burst(index) shouldBe (level plusOrMinus level * LEVEL_TOLERANCE)
      }
      if (TimelineBursts.burstStart(index + 1) <= duration) {
        withClue("the silence after burst $index") { gap(index) shouldBeLessThan level * SILENT }
      }
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val TRIM: TimeRange = TimeRange.of(200.milliseconds, 1_300.milliseconds)
    val EDGE: Duration = 20.milliseconds
    const val LOUD = 0.5f
    const val QUARTER = 0.25f

    // An AAC encoder holds a steady tone's level to a few percent.
    const val LEVEL_TOLERANCE = 0.15f
    const val SILENT = 0.05f
  }
}
