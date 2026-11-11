package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toPreviewComposition
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What a preview's composition plays when its plan has audio effects, read through its own audio mix the way its
 * player mixes it, and held against what an export of the same edit writes.
 *
 * A player hands nobody its mixed samples, so the taps on a preview's tracks run each clip's effects, apply its gain
 * and then run the composition's effects, where an export leaves the gain to the mix and runs the composition's
 * effects on the mixed samples.
 */
@OptIn(ExperimentalForeignApi::class)
class ApplePreviewAudioEffectMediaTest {
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addAudioEffectResolver(testAudioEffects)
      .build()
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      addAudioEffectResolver(testAudioEffects)
    }
  private val source = MediaSource.of(fixture(TONE))

  @Test
  fun aClipEffectRunsAheadOfTheClipsLevelAndACompositionEffectAfterIt() =
    runTest(timeout = TIMEOUT) {
      val quiet = Clip(source, TRIM, audio = AudioLevel.Volume(QUARTER))

      val clipScope = previewed(edit(quiet.withAudioEffects(listOf(TimelineBursts(LOUD)))))
      val mixScope = previewed(edit(quiet).withAudioEffects(listOf(TimelineBursts(LOUD))))

      clipScope.shouldHearBursts(0..2, LOUD * QUARTER)
      mixScope.shouldHearBursts(0..2, LOUD)
    }

  // The second burst sits half way up the fade, where a ramp read as a step or measured from the wrong end would
  // not agree with the export's.
  @Test
  fun aFadeTakesAClipEffectDownAsFarAsTheExportDoes() =
    runTest(timeout = TIMEOUT) {
      val edit =
        edit(Clip(source, TRIM, fadeIn = TimelineBursts.PERIOD * 2, audioEffects = listOf(TimelineBursts(LOUD))))

      val preview = previewed(edit)
      val export = exported(edit)

      withClue("the burst under the start of the fade") { preview.burst(0) shouldBeLessThan LOUD * QUARTER }
      withClue("the burst half way up the fade") {
        preview.burst(1) shouldBe (export.burst(1) plusOrMinus LOUD * PARITY)
      }
      preview.shouldHearBursts(2..2, LOUD)
    }

  // A player stretches a scaled track after its tap, which would stretch the bursts with the clip.
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

      previewed(edit(clip)).shouldHearBursts(0..2, LOUD)
    }

  // Both tracks run the composition's effect, and only the silence laid under them keeps the bursts it adds. The
  // fixture's own tone comes through from both.
  @Test
  fun twoTracksHearACompositionEffectsOwnSoundOnce() =
    runTest(timeout = TIMEOUT) {
      val bed = Track(listOf(Clip(source, TRIM)), content = TrackContent.Audio)
      val edit =
        Composition(listOf(Track(listOf(Clip(source, TRIM))), bed))
          .withAudioEffects(listOf(AddedBursts(LOUD), Delay(LATENCY_FRAMES)))

      val preview = previewed(edit)
      val export = exported(edit)

      preview.shouldHearBursts(0..2, LOUD)
      withClue("the fixture's tone under the effect, from both tracks") {
        preview.fixtureTone() shouldBe (export.fixtureTone() plusOrMinus export.fixtureTone() * PARITY)
      }
    }

  // The bed ends a third of the way in and the picture track lays no audio. A tap is not run past the end of its
  // track, so it is the silence laid under the whole timeline that carries the effect to the end.
  @Test
  fun aCompositionEffectIsHeardPastTheEndOfTheLastAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(MediaSource.of(fixture(PICTURE)), TRIM)), content = TrackContent.Video)
      val bed = Track(listOf(Clip(source, TimeRange.of(200.milliseconds, 500.milliseconds))), TrackContent.Audio)

      val preview = previewed(Composition(listOf(picture, bed)).withAudioEffects(listOf(TimelineBursts(LOUD))))

      preview.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aCompositionEffectIsHeardOverAnEditWithNoAudioTrackAtAll() =
    runTest(timeout = TIMEOUT) {
      val picture = Track(listOf(Clip(MediaSource.of(fixture(PICTURE)), TRIM)), content = TrackContent.Video)

      val preview = previewed(Composition(listOf(picture)).withAudioEffects(listOf(TimelineBursts(LOUD))))

      preview.shouldHearBursts(0..2, LOUD)
    }

  @Test
  fun aChangedEffectAndLevelSwapIntoTheStandingTaps() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(source, TRIM)
      val before = resolved(edit(clip.withAudioEffects(listOf(TimelineBursts(LOUD)))))
      val after =
        resolved(
          edit(Clip(source, TRIM, audio = AudioLevel.Volume(QUARTER)).withAudioEffects(listOf(TimelineBursts(LOUD))))
            .withAudioEffects(listOf(Delay(LATENCY_FRAMES))),
        )
      val av = lowered(before)
      val mix = av.audioMix.shouldNotBeNull()

      av.takesParametersOf(after) shouldBe true
      av.updateParameters(after)

      av.audioMix shouldBeSameInstanceAs mix
      heard(av).shouldHearBursts(0..2, LOUD * QUARTER)
      av.discard()
    }

  // No tap stands in a plan without an audio effect, so the level is a ramp in a mix the player item is handed anew.
  @Test
  fun aChangedLevelWithNoEffectWritesANewMix() =
    runTest(timeout = TIMEOUT) {
      val before = resolved(edit(Clip(source, TRIM, audio = AudioLevel.Volume(HALF))))
      val after = resolved(edit(Clip(source, TRIM, audio = AudioLevel.Volume(QUARTER))))
      val av = lowered(before)
      val mix = av.audioMix.shouldNotBeNull()
      val loud = heard(av).fixtureTone()

      av.takesParametersOf(after) shouldBe true
      av.updateParameters(after)

      av.audioMix.shouldNotBeNull() shouldNotBeSameInstanceAs mix
      heard(av).fixtureTone() shouldBe (loud / 2 plusOrMinus loud * PARITY)
    }

  @Test
  fun gainingTheFirstAudioEffectOrLosingTheLastNeedsANewComposition() =
    runTest(timeout = TIMEOUT) {
      val plain = resolved(edit(Clip(source, TRIM)))
      val effected = resolved(edit(Clip(source, TRIM)).withAudioEffects(listOf(TimelineBursts(LOUD))))

      lowered(plain).takesParametersOf(effected) shouldBe false
      lowered(effected).also { it.takesParametersOf(plain) shouldBe false }.discard()
    }

  private fun edit(clip: Clip): Composition = Composition(listOf(Track(listOf(clip))))

  private suspend fun previewed(edit: Composition): AudioProbe {
    val av = lowered(resolved(edit))
    return heard(av).also { av.discard() }
  }

  private suspend fun lowered(resolved: ResolvedComposition): AvComposition =
    withContext(Dispatchers.Default) { resolved.toPreviewComposition() }

  private suspend fun heard(av: AvComposition): AudioProbe =
    withContext(Dispatchers.Default) { mixedAudioOf(av.composition, av.audioMix) }

  private suspend fun resolved(edit: Composition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      when (val result = avFoundationExportEngine(components).resolve(edit, ExportSpec())) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private suspend fun exported(edit: Composition): AudioProbe =
    withContext(Dispatchers.Default) {
      val output = NSTemporaryDirectory() + "filmstrip-apple-preview-audio-effect-" + NSUUID().UUIDString + ".mp4"
      val finished = filmstrip.export(edit, ExportSpec(), MediaSink.of(output)).toList().last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      audioOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
    }

  private fun AudioProbe.fixtureTone(): Float = toneOver(READING, WINDOW, FIXTURE_HZ)

  private companion object {
    val TIMEOUT = 3.minutes
    const val TONE = "tone_bed_880.mp4"
    const val PICTURE = "apple_export_a.mp4"
    const val FIXTURE_HZ = 880
    val TRIM: TimeRange = TimeRange.of(200.milliseconds, 1_300.milliseconds)

    // Between two bursts, where only the fixture's own tone sounds.
    val READING: Duration = 150.milliseconds
    val WINDOW: Duration = 200.milliseconds
    const val LOUD = 0.5f
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val LATENCY_FRAMES = 480

    // How far a preview's level may sit from the export's, which went through an AAC encoder.
    const val PARITY = 0.1f
  }
}
