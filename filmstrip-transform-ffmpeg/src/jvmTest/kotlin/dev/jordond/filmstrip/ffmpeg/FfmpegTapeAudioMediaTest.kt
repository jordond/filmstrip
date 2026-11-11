package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioParams
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.AUDIO_SAMPLE_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.PitchBend
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A [TapeAudio] written as ffmpeg filters, read back as decoded samples and held to [tapeAudioParams].
 *
 * [TestTones] runs ahead of it as a filter expression, so the tape is handed tones whose level and pitch are known.
 */
@OptIn(InternalFilmstripApi::class)
class FfmpegTapeAudioMediaTest {
  private val fixture = FfmpegExportFixture()
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      filters()
      addAudioEffectResolver(TONES)
    }
  private val source = MediaSource.of(fixture.landscape.absolutePath)

  @Test
  fun `a steady tape narrows the band to its corners and lays its hiss under the sound`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val tape = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val params = tapeAudioParams(tape, AUDIO_SAMPLE_RATE)
      val corner = params.lowPassHz.toInt()
      val tones = TestTones(listOf(100, 1_000, corner, corner * 2), CHORD, TONES_END)

      val samples = export(overTimeline(tones, tape))

      val middle = samples.tone(1_000)
      middle shouldBe (CHORD * MONO_FOLD plusOrMinus CHORD * MONO_FOLD * LEVEL_TOLERANCE)
      samples.tone(params.highPassHz.toInt()) / middle shouldBe (CORNER plusOrMinus CORNER_TOLERANCE)
      samples.tone(corner) / middle shouldBe (CORNER plusOrMinus CORNER_TOLERANCE)
      samples.tone(corner * 2) / middle shouldBeLessThan STOPPED
      samples.hiss() shouldBe (params.hissRms.toFloat() plusOrMinus params.hissRms.toFloat() * HISS_TOLERANCE)
    }

  @Test
  fun `the default tape bends a tone by the wow and the flutter the params give`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val tape = TapeAudio()
      val params = tapeAudioParams(tape, AUDIO_SAMPLE_RATE)

      val samples = export(overTimeline(TestTones(listOf(CARRIER), TONE, WHOLE), tape))

      val bend = samples.bend(BEND_FROM, cycles(2, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * BEND_TOLERANCE)
    }

  @Test
  fun `two exports of one tape decode to the same samples`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition = overTimeline(TestTones(listOf(CARRIER), TONE, TONES_END), TapeAudio())

      export(composition).toList() shouldBe export(composition).toList()
    }

  @Test
  fun `a clip's tape runs after its speed and ahead of its level and fade`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val tape = TapeAudio(flutter = Wobble(0f))
      val params = tapeAudioParams(tape, AUDIO_SAMPLE_RATE)
      val composition =
        compositionOf {
          clip(source) {
            speed(HALF_SPEED)
            audio(AudioLevel.Volume(CLIP_GAIN))
            fadeOut(FADE)
            audioEffects {
              add(TestTones(listOf(CARRIER), TONE, TONES_END))
              add(tape)
            }
          }
        }

      val samples = export(composition)

      val level = TONE * CLIP_GAIN * MONO_FOLD
      samples.tone(CARRIER) shouldBe (level plusOrMinus level * LEVEL_TOLERANCE)
      val bend = samples.bend(BEND_FROM, cycles(1, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      val hiss = params.hissRms.toFloat() * CLIP_GAIN
      samples.hiss() shouldBe (hiss plusOrMinus hiss * HISS_TOLERANCE)
      samples.rms(WHOLE - FADE / 4, FADE / 8) shouldBeLessThan hiss * MONO_FOLD * FADED
    }

  @Test
  fun `a muted clip's tape is silent`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition =
        compositionOf {
          clip(source) {
            audio(AudioLevel.Mute)
            audioEffects { add(TapeAudio()) }
          }
        }

      export(composition).rms(Duration.ZERO, 2.seconds) shouldBe 0f
    }

  // Two passes of the fixture with the effects on the composition, so they run on the whole timeline.
  private fun overTimeline(
    tones: TestTones,
    tape: TapeAudio,
  ): Composition =
    compositionOf {
      clip(source)
      clip(source)
      audioEffects {
        add(tones)
        add(tape)
      }
    }

  private suspend fun export(composition: Composition): FloatArray {
    val output = File.createTempFile("filmstrip-tape-audio", ".mp4").also { it.delete() }
    try {
      val finished = filmstrip.export(composition, SPEC, MediaSink.of(output.absolutePath)).toList().last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()
      return decodedAudio(output.absolutePath)
    } finally {
      output.delete()
    }
  }

  private fun FloatArray.tone(hz: Int): Float =
    ToneAnalysis.levelOver(this, frames(TONE_FROM), frames(TONE_READ), hz, AUDIO_SAMPLE_RATE)

  // The hiss as one channel carries it, read where the tones have stopped.
  private fun FloatArray.hiss(): Float = rms(HISS_FROM, HISS_READ) / MONO_FOLD

  private fun FloatArray.rms(
    from: Duration,
    length: Duration,
  ): Float = ToneAnalysis.rmsOver(this, frames(from), frames(length))

  private fun FloatArray.bend(
    from: Duration,
    length: Duration,
  ): PitchBend = PitchBend.of(this, frames(from), frames(length), CARRIER, AUDIO_SAMPLE_RATE)

  private fun frames(time: Duration): Int = (time.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).toInt()

  private fun cycles(
    count: Int,
    rateHz: Double,
  ): Duration = (count / rateHz).seconds

  private companion object {
    const val CHORD = 0.1f
    const val TONE = 0.4f
    const val CARRIER = 1_000
    const val CLIP_GAIN = 0.5f
    const val HALF_SPEED = 0.5
    const val CORNER = 0.7071f

    // What an AAC encoder and the read of a short window leave of a level, a corner and a noise floor.
    const val LEVEL_TOLERANCE = 0.1f
    const val CORNER_TOLERANCE = 0.07f
    const val HISS_TOLERANCE = 0.2f
    const val STOPPED = 0.1f
    const val FADED = 0.5f

    // ffmpeg's vibrato reads between two samples and rounds its rate to a whole table.
    const val BEND_TOLERANCE = 0.1

    // The probe folds the export's two channels to one at full level each over the square root of two, and the tape
    // writes the same samples to both, so a level reads that much higher than it was written.
    val MONO_FOLD = sqrt(2f)
    val SPEC = ExportSpec(heightPx = 240)
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
        (spec as? TestTones)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(listOf(tonesFilter(it)))) }
      }

    fun tonesFilter(tones: TestTones): FilterNode {
      val chord = tones.frequenciesHz.joinToString("+") { "sin(2*PI*$it*t)" }
      val length = tones.length.toDouble(DurationUnit.SECONDS)
      return FilterNode("aeval", "exprs" to "${tones.amplitude}*($chord)*lt(t,$length)", "c" to "same")
    }
  }
}
