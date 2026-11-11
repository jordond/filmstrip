package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.avfoundation.internal.composition.toPreviewComposition
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterAudioEffectResolver
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioParams
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.PitchBend
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A [TapeAudio] in an AVFoundation export, read back as decoded samples and held to [tapeAudioParams].
 *
 * [TestTones] runs ahead of it, so the tape is handed tones whose level and pitch are known.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class AppleTapeAudioMediaTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
      addAudioEffectResolver(TONES)
    }
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addAudioEffectResolver(TONES)
      .addAudioEffectResolver(FilterAudioEffectResolver())
      .build()
  private val source = MediaSource.of(fixture(LONG))

  @Test
  fun aSteadyTapeNarrowsTheBandToItsCornersAndLaysItsHissUnderTheSound() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val params = tapeAudioParams(tape, PROBE_SAMPLE_RATE)
      val corner = params.lowPassHz.toInt()
      val tones = TestTones(listOf(100, 1_000, corner, corner * 2), CHORD, TONES_END)

      val audio = exported(overTimeline(tones, tape))

      val middle = audio.tone(1_000)
      middle shouldBe (CHORD plusOrMinus CHORD * LEVEL_TOLERANCE)
      audio.tone(params.highPassHz.toInt()) / middle shouldBe (CORNER plusOrMinus CORNER_TOLERANCE)
      audio.tone(corner) / middle shouldBe (CORNER plusOrMinus CORNER_TOLERANCE)
      audio.tone(corner * 2) / middle shouldBeLessThan STOPPED
      audio.hiss() shouldBe (params.hissRms.toFloat() plusOrMinus params.hissRms.toFloat() * HISS_TOLERANCE)
    }

  @Test
  fun theDefaultTapeBendsAToneByTheWowAndTheFlutterTheParamsGive() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio()
      val params = tapeAudioParams(tape, PROBE_SAMPLE_RATE)

      val audio = exported(overTimeline(TestTones(listOf(CARRIER), TONE, WHOLE), tape))

      val bend = audio.bend(cycles(2, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * BEND_TOLERANCE)
    }

  // AVFoundation's limit, not the tape's: two exports of an edit with no effect at all decode a few parts in ten
  // million apart, so the same tape is held to that and another seed is shown to sit far outside it.
  @Test
  fun twoExportsOfOneTapeDecodeToTheSameSamples() =
    runTest(timeout = TIMEOUT) {
      val edit = overTimeline(TestTones(listOf(CARRIER), TONE, TONES_END), TapeAudio())

      val first = exported(edit).samples
      val second = exported(edit).samples

      second.size shouldBe first.size
      first.indices.maxOf { abs(first[it] - second[it]) } shouldBeLessThan CODEC_JITTER
      val other = exported(overTimeline(TestTones(listOf(CARRIER), TONE, TONES_END), TapeAudio(seed = 7))).samples
      first.indices.maxOf { abs(first[it] - other[it]) } shouldBeGreaterThan CODEC_JITTER * OTHER_TAPE
    }

  @Test
  fun aClipsTapeRunsAfterItsSpeedAndAheadOfItsLevelAndFade() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio(flutter = Wobble(0f))
      val params = tapeAudioParams(tape, PROBE_SAMPLE_RATE)
      val clip =
        Clip(
          source,
          TimeRange.of(Duration.ZERO, WHOLE / 2),
          audio = AudioLevel.Volume(CLIP_GAIN),
          fadeOut = FADE,
          speed = HALF_SPEED,
          audioEffects = listOf(TestTones(listOf(CARRIER), TONE, TONES_END), tape),
        )

      val audio = exported(Composition(listOf(Track(listOf(clip)))))

      audio.tone(CARRIER) shouldBe (TONE * CLIP_GAIN plusOrMinus TONE * CLIP_GAIN * LEVEL_TOLERANCE)
      val bend = audio.bend(cycles(1, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      val hiss = params.hissRms.toFloat() * CLIP_GAIN
      audio.hiss() shouldBe (hiss plusOrMinus hiss * HISS_TOLERANCE)
      audio.rms(WHOLE - FADE / 4, FADE / 8) shouldBeLessThan hiss * FADED
    }

  // A preview's tap cannot hand a frame back early, so its sound runs late by the kernel's latency. That moves no
  // level and no bend, which are what is read here.
  @Test
  fun aPreviewPlaysTheBandTheHissAndTheBendTheParamsGive() =
    runTest(timeout = TIMEOUT) {
      val steady = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val params = tapeAudioParams(TapeAudio(), PROBE_SAMPLE_RATE)
      val corner = params.lowPassHz.toInt()
      val chord = TestTones(listOf(100, 1_000, corner, corner * 2), CHORD, TONES_END)

      val band = previewed(overTimeline(chord, steady))
      val bent = previewed(overTimeline(TestTones(listOf(CARRIER), TONE, WHOLE), TapeAudio()))

      val middle = band.tone(1_000)
      middle shouldBe (CHORD plusOrMinus CHORD * PREVIEW_TOLERANCE)
      band.tone(params.highPassHz.toInt()) / middle shouldBe (CORNER plusOrMinus PREVIEW_TOLERANCE)
      band.tone(corner) / middle shouldBe (CORNER plusOrMinus PREVIEW_TOLERANCE)
      band.tone(corner * 2) / middle shouldBeLessThan STOPPED
      band.hiss() shouldBe (params.hissRms.toFloat() plusOrMinus params.hissRms.toFloat() * PREVIEW_HISS_TOLERANCE)
      val bend = bent.bend(cycles(2, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * PREVIEW_BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe
        (params.flutterDepth plusOrMinus params.flutterDepth * PREVIEW_BEND_TOLERANCE)
    }

  @Test
  fun aMutedClipsTapeIsSilent() =
    runTest(timeout = TIMEOUT) {
      val clip =
        Clip(
          source,
          TimeRange.of(Duration.ZERO, TONES_END),
          audio = AudioLevel.Mute,
          audioEffects = listOf(TapeAudio()),
        )

      exported(Composition(listOf(Track(listOf(clip))))).rms(Duration.ZERO, TONES_END) shouldBe 0f
    }

  private fun overTimeline(
    tones: TestTones,
    tape: TapeAudio,
  ): Composition =
    Composition(listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, WHOLE))))))
      .withAudioEffects(listOf(tones, tape))

  private suspend fun exported(edit: Composition): AudioProbe =
    withContext(Dispatchers.Default) {
      val output = NSTemporaryDirectory() + "filmstrip-apple-tape-audio-" + NSUUID().UUIDString + ".mp4"
      val finished = filmstrip.export(edit, ExportSpec(), MediaSink.of(output)).toList().last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      audioOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
    }

  // What the preview's own composition and audio mix play, read the way its player mixes them.
  private suspend fun previewed(edit: Composition): AudioProbe =
    withContext(Dispatchers.Default) {
      val resolved =
        when (val result = avFoundationExportEngine(components).resolve(edit, ExportSpec())) {
          is ResolveResult.Refused -> error(result.error.message)
          is ResolveResult.Resolved -> result.composition
        }
      val av = resolved.toPreviewComposition()
      mixedAudioOf(av.composition, av.audioMix).also { av.discard() }
    }

  private fun AudioProbe.tone(hz: Int): Float =
    ToneAnalysis.levelOver(samples, frames(TONE_FROM), frames(TONE_READ), hz, sampleRate)

  private fun AudioProbe.hiss(): Float = rms(HISS_FROM, HISS_READ)

  private fun AudioProbe.rms(
    from: Duration,
    length: Duration,
  ): Float = ToneAnalysis.rmsOver(samples, frames(from), frames(length))

  private fun AudioProbe.bend(length: Duration): PitchBend =
    PitchBend.of(samples, frames(BEND_FROM), frames(length), CARRIER, sampleRate)

  private fun AudioProbe.frames(time: Duration): Int = (time.toDouble(DurationUnit.SECONDS) * sampleRate).toInt()

  private fun cycles(
    count: Int,
    rateHz: Double,
  ): Duration = (count / rateHz).seconds

  private companion object {
    val TIMEOUT = 3.minutes
    const val LONG = "apple_export_long.mp4"
    const val CHORD = 0.1f
    const val TONE = 0.4f
    const val CARRIER = 1_000
    const val CLIP_GAIN = 0.5f
    const val HALF_SPEED = 0.5
    const val CORNER = 0.7071f

    // What an AAC encoder and the read of a short window leave of a level, a corner, a noise floor and a bend.
    const val LEVEL_TOLERANCE = 0.1f
    const val CORNER_TOLERANCE = 0.07f
    const val HISS_TOLERANCE = 0.2f
    const val STOPPED = 0.1f
    const val FADED = 0.5f
    const val BEND_TOLERANCE = 0.1
    const val CODEC_JITTER = 1e-5f

    // Nothing is encoded on the way out of a preview, so it is held closer.
    const val PREVIEW_TOLERANCE = 0.03f
    const val PREVIEW_HISS_TOLERANCE = 0.05f
    const val PREVIEW_BEND_TOLERANCE = 0.05
    const val OTHER_TAPE = 100
    val WHOLE = 4.seconds
    val TONES_END = 2.seconds
    val TONE_FROM = 500.milliseconds
    val TONE_READ = 1.seconds
    val HISS_FROM = 2_500.milliseconds
    val HISS_READ = 1.seconds
    val BEND_FROM = 250.milliseconds
    val FADE = 800.milliseconds

    val TONES =
      AudioEffectResolver { spec, _ ->
        (spec as? TestTones)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(it::newKernel)) }
      }
  }
}
