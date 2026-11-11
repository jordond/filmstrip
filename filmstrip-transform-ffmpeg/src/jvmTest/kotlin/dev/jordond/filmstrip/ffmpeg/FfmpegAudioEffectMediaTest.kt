package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.AUDIO_SAMPLE_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Audio effects written as ffmpeg filters, read back as decoded samples.
 *
 * The test effect is [TimelineBursts] spelled as a filter expression over `t`, so where a burst is heard says which
 * clock the filter read and how loud it is says what ran after it.
 */
class FfmpegAudioEffectMediaTest {
  private val fixture = FfmpegExportFixture()
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      addAudioEffectResolver(FILTERS)
    }
  private val source = MediaSource.of(fixture.landscape.absolutePath)

  // The second clip opens off the burst grid, so bursts counted from the clip's own start would land elsewhere.
  @Test
  fun `a clip's effect reads composition time and runs ahead of the clip's level`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition =
        compositionOf {
          clip(source) { trim(Duration.ZERO..FIRST_CLIP) }
          clip(source) {
            audio(AudioLevel.Volume(CLIP_GAIN))
            audioEffects { add(FilterBursts(AMPLITUDE)) }
          }
        }

      val samples = export(composition)

      samples.toneOver(FIRST_CLIP / 2, READ, TimelineBursts.TONE_HZ) shouldBeLessThan AMPLITUDE * SILENT
      samples.shouldHearBursts(SECOND_CLIP_BURSTS, AMPLITUDE * CLIP_GAIN)
    }

  @Test
  fun `the composition's effect runs on the mix after every level`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition =
        compositionOf {
          clip(source) { audio(AudioLevel.Volume(CLIP_GAIN)) }
          audioEffects { add(FilterBursts(AMPLITUDE)) }
        }

      export(composition).shouldHearBursts(1..3, AMPLITUDE)
    }

  @Test
  fun `an effect with no ffmpeg lowering leaves the audio clean`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition = compositionOf { clip(source) { audioEffects { add(NoLowering) } } }

      val refused = filmstrip.plan(composition, SPEC).shouldBeInstanceOf<Verdict.Incapable>()
      val without = refused.withoutUnsupported.shouldNotBeNull()
      val samples = decoded { output -> filmstrip.export(without.plan, MediaSink.of(output)).toList().last() }

      without.adjustments.map { it.kind } shouldContain AdjustmentKind.EffectDropped
      samples.toneOver(BURST_EDGE, READ, PRIMARY_HZ) shouldBeGreaterThan AUDIBLE
    }

  private suspend fun export(composition: Composition): FloatArray =
    decoded { output -> filmstrip.export(composition, SPEC, MediaSink.of(output)).toList().last() }

  private suspend fun decoded(run: suspend (String) -> ExportStatus): FloatArray {
    val output = File.createTempFile("filmstrip-audio-effect", ".mp4").also { it.delete() }
    try {
      val finished = run(output.absolutePath)
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()
      return decodedAudio(output.absolutePath)
    } finally {
      output.delete()
    }
  }

  private fun FloatArray.shouldHearBursts(
    bursts: IntRange,
    level: Float,
  ) {
    bursts.forEach { index ->
      val opens = TimelineBursts.burstStart(index)
      withClue("burst $index, opening at $opens") {
        toneOver(opens + BURST_EDGE, READ, TimelineBursts.TONE_HZ) shouldBe
          (level * MONO_FOLD plusOrMinus level * TOLERANCE)
      }
      withClue("the silence after burst $index") {
        toneOver(opens + TimelineBursts.LENGTH + BURST_EDGE, GAP_READ, TimelineBursts.TONE_HZ) shouldBeLessThan
          level * SILENT
      }
    }
  }

  private fun FloatArray.toneOver(
    from: Duration,
    length: Duration,
    frequencyHz: Int,
  ): Float =
    ToneAnalysis.amplitudeOver(
      samples = this,
      from = (from.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).toInt(),
      length = (length.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).toInt(),
      frequencyHz = frequencyHz,
      sampleRate = AUDIO_SAMPLE_RATE,
    )

  private data class FilterBursts(
    val amplitude: Float,
  ) : AudioEffectSpec {
    override val id: String get() = "test.filter-bursts"
  }

  private data object NoLowering : AudioEffectSpec {
    override val id: String get() = "test.no-lowering"
  }

  private companion object {
    const val AMPLITUDE = 0.5f
    const val CLIP_GAIN = 0.5f

    // An AAC encoder holds a steady tone's level to a few percent.
    const val TOLERANCE = 0.15f
    const val SILENT = 0.05f
    const val AUDIBLE = 0.01f

    // The probe folds the export's two channels to one at full level each over the square root of two, and an effect
    // here writes the same samples to both, so its tone reads that much higher than it was written.
    val MONO_FOLD = sqrt(2f)
    val SPEC = ExportSpec(heightPx = 240)
    val FIRST_CLIP = 1_500.milliseconds
    val SECOND_CLIP_BURSTS = 4..7
    val BURST_EDGE = 20.milliseconds
    val READ = TimelineBursts.LENGTH - BURST_EDGE * 2
    val GAP_READ = TimelineBursts.PERIOD - TimelineBursts.LENGTH - BURST_EDGE * 2

    val FILTERS =
      AudioEffectResolver { spec, _ ->
        when (spec) {
          is FilterBursts -> AudioEffectResolution.Resolved(PlatformAudioEffect(listOf(burstsFilter(spec.amplitude))))
          NoLowering -> AudioEffectResolution.Unsupported(spec.id, "This effect has no ffmpeg filter.")
          else -> null
        }
      }

    fun burstsFilter(amplitude: Float): FilterNode {
      val period = TimelineBursts.PERIOD.toDouble(DurationUnit.SECONDS)
      val length = TimelineBursts.LENGTH.toDouble(DurationUnit.SECONDS)
      val tone = "$amplitude*sin(2*PI*${TimelineBursts.TONE_HZ}*t)*lt(mod(t,$period),$length)"
      return FilterNode("aeval", "exprs" to tone, "c" to "same")
    }
  }
}
