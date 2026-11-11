package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioParams
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.PitchBend
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A [TapeAudio] in a media3 export, read back as decoded samples and held to [tapeAudioParams].
 *
 * [TestTones] runs ahead of it, so the tape is handed tones whose level and pitch are known.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidTapeAudioMediaTest {
  private val probe =
    Media3ExportProbe {
      filters()
      addAudioEffectResolver(TONES)
    }
  private val source: MediaSource get() = probe.fixture(LONG)

  @Test
  fun aSteadyTapeNarrowsTheBandToItsCornersAndLaysItsHissUnderTheSound() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val params = tapeAudioParams(tape, FIXTURE_RATE)
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
      val params = tapeAudioParams(tape, FIXTURE_RATE)

      val audio = exported(overTimeline(TestTones(listOf(CARRIER), TONE, WHOLE), tape))

      val bend = audio.bend(cycles(2, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * BEND_TOLERANCE)
    }

  @Test
  fun twoExportsOfOneTapeDecodeToTheSameSamples() =
    runTest(timeout = TIMEOUT) {
      val edit = overTimeline(TestTones(listOf(CARRIER), TONE, TONES_END), TapeAudio())

      exported(edit).samples.toList() shouldBe exported(edit).samples.toList()
    }

  @Test
  fun aClipsTapeRunsAfterItsSpeedAndAheadOfItsLevelAndFade() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio(flutter = Wobble(0f))
      val params = tapeAudioParams(tape, FIXTURE_RATE)
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

  private suspend fun exported(edit: Composition): DecodedAudio {
    val file = probe.export(edit, ExportSpec())
    return decodeAudio(file).also { file.delete() }
  }

  private fun DecodedAudio.tone(hz: Int): Float =
    ToneAnalysis.levelOver(samples, frames(TONE_FROM), frames(TONE_READ), hz, sampleRate)

  private fun DecodedAudio.hiss(): Float = rms(HISS_FROM, HISS_READ)

  private fun DecodedAudio.rms(
    from: Duration,
    length: Duration,
  ): Float = ToneAnalysis.rmsOver(samples, frames(from), frames(length))

  private fun DecodedAudio.bend(length: Duration): PitchBend =
    PitchBend.of(samples, frames(BEND_FROM), frames(length), CARRIER, sampleRate)

  private fun DecodedAudio.frames(time: Duration): Int = (time.toDouble(DurationUnit.SECONDS) * sampleRate).toInt()

  private fun cycles(
    count: Int,
    rateHz: Double,
  ): Duration = (count / rateHz).seconds

  private companion object {
    val TIMEOUT = 3.minutes
    const val LONG = "android_export_long.mp4"
    const val FIXTURE_RATE = 48_000
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
