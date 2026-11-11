package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.filters.FilterAudioEffectResolver
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioParams
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.test.PitchBend
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A [TapeAudio] in a media3 preview, read in what `CompositionPlayer` hands its audio sink and held to
 * [tapeAudioParams], the figures the export's own device test holds the file to.
 *
 * [TestTones] runs ahead of it, so the tape is handed tones whose level and pitch are known.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidPreviewTapeAudioMediaTest {
  @Test
  fun aPreviewPlaysTheBandAndTheHissTheParamsGive() =
    runTest(timeout = TIMEOUT) {
      val tape = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val corner = tapeAudioParams(tape, RATE).lowPassHz.toInt()
      val chord = TestTones(listOf(100, 1_000, corner, corner * 2), CHORD, TONES_END)

      val heard = heard(planOf(edit(chord, tape)))

      val params = tapeAudioParams(tape, heard.sampleRate)
      val middle = heard.tone(1_000)
      middle shouldBe (CHORD plusOrMinus CHORD * TOLERANCE)
      heard.tone(params.highPassHz.toInt()) / middle shouldBe (CORNER plusOrMinus TOLERANCE)
      heard.tone(corner) / middle shouldBe (CORNER plusOrMinus TOLERANCE)
      heard.tone(corner * 2) / middle shouldBeLessThan STOPPED
      val hiss = ToneAnalysis.rmsOver(heard.samples, heard.frames(HISS_FROM), heard.frames(HISS_READ))
      hiss shouldBe (params.hissRms.toFloat() plusOrMinus params.hissRms.toFloat() * HISS_TOLERANCE)
    }

  @Test
  fun aPreviewBendsAToneByTheWowAndTheFlutterTheParamsGive() =
    runTest(timeout = TIMEOUT) {
      val heard = heard(planOf(edit(TestTones(listOf(CARRIER), TONE, WHOLE), TapeAudio())))

      val params = tapeAudioParams(TapeAudio(), heard.sampleRate)
      val length = heard.frames((2 / params.wowRateHz).seconds)
      val bend = PitchBend.of(heard.samples, heard.frames(BEND_FROM), length, CARRIER, heard.sampleRate)
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * BEND_TOLERANCE)
    }

  // Three passes of the fixture's first second and a half, with the effects on the composition.
  private fun edit(
    tones: TestTones,
    tape: TapeAudio,
  ): Composition {
    val clip = Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, PASS))
    return Composition(listOf(Track(listOf(clip, clip, clip)))).withAudioEffects(listOf(tones, tape))
  }

  private suspend fun planOf(edit: Composition): Media3PreviewPlan =
    withContext(Dispatchers.Default) {
      Media3PreviewPlanner(COMPONENTS).plan(edit, PreviewQualityPolicy.Full, ExportSpec())
    }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

  private suspend fun heard(plan: Media3PreviewPlan): HeardAudio =
    withContext(Dispatchers.IO) { playedThrough(plan.resolved.toMedia3Preview(plan.bounds).composition) }

  private fun HeardAudio.tone(hz: Int): Float =
    ToneAnalysis.levelOver(samples, frames(TONE_FROM), frames(TONE_READ), hz, sampleRate)

  private fun HeardAudio.frames(time: Duration): Int = (time.toDouble(DurationUnit.SECONDS) * sampleRate).toInt()

  private companion object {
    val TIMEOUT = 3.minutes
    const val RATE = 48_000
    const val CHORD = 0.1f
    const val TONE = 0.4f
    const val CARRIER = 1_000
    const val CORNER = 0.7071f

    // Nothing is encoded on the way to the sink, which takes 16-bit samples.
    const val TOLERANCE = 0.03f
    const val HISS_TOLERANCE = 0.05f
    const val STOPPED = 0.08f
    const val BEND_TOLERANCE = 0.05
    val PASS = 1_500.milliseconds
    val WHOLE = 4.seconds
    val TONES_END = 2.seconds
    val TONE_FROM = 500.milliseconds
    val TONE_READ = 1.seconds
    val HISS_FROM = 2_500.milliseconds
    val HISS_READ = 1.seconds
    val BEND_FROM = 250.milliseconds

    val COMPONENTS: ComponentRegistry =
      ComponentRegistry
        .Builder(CONTRACT_COMPONENTS)
        .addAudioEffectResolver(
          AudioEffectResolver { spec, _ ->
            (spec as? TestTones)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(it::newKernel)) }
          },
        ).addAudioEffectResolver(FilterAudioEffectResolver())
        .build()
  }
}
