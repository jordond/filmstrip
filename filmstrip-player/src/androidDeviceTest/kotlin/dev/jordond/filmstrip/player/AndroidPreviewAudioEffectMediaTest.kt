package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.assertions.withClue
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

/**
 * Where an audio effect's kernel runs in a media3 preview, read off [TimelineBursts] in what `CompositionPlayer` hands
 * its audio sink.
 *
 * An export of the same edits is read the same way in the media3 backend's own device tests, so a burst heard on the
 * timeline here is heard where the export writes it.
 */
class AndroidPreviewAudioEffectMediaTest {
  // The second clip opens between two bursts, trimmed from past the source's start, so a kernel counting from the
  // clip or from the source would put its burst somewhere else.
  @Test
  fun aPreviewLaysATrackEffectsBurstsOnTheTimelineAcrossTrimmedClips() =
    runTest(timeout = TIMEOUT) {
      val heard = heard(planOf(edit(TimelineBursts(LOUD))))

      heard.shouldHearBursts(LOUD)
    }

  @Test
  fun aPreviewRunsACompositionEffectOnTheMix() =
    runTest(timeout = TIMEOUT) {
      val edit = edit(null).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val heard = heard(planOf(edit))

      heard.shouldHearBursts(LOUD)
    }

  // The picture track lays no audio, so nothing but the silence the lowering lays under the timeline carries the
  // composition's effect.
  @Test
  fun aPreviewRunsACompositionEffectOverAnEditWithNoAudioTrack() =
    runTest(timeout = TIMEOUT) {
      val picture =
        Track(
          listOf(Clip(androidFixtureClip(), TimeRange.of(200.milliseconds, 1_300.milliseconds))),
          content = TrackContent.Video,
        )
      val edit = Composition(listOf(picture)).withAudioEffects(listOf(TimelineBursts(LOUD)))

      val heard = heard(planOf(edit))

      heard.shouldHearBursts(LOUD)
    }

  @Test
  fun aChangedEffectReachesTheStandingGraphWithoutARebuild() =
    runTest(timeout = TIMEOUT) {
      val standing = planOf(edit(TimelineBursts(LOUD)))
      val preview = standing.resolved.toMedia3Preview(standing.bounds)
      val next = planOf(edit(TimelineBursts(QUIET)))

      preview.updateParameters(next.resolved, next.bounds) shouldBe true

      val heard = withContext(Dispatchers.IO) { playedThrough(preview.composition) }
      heard.shouldHearBursts(QUIET)
    }

  private fun edit(trackEffect: TimelineBursts?): Composition {
    val source = androidFixtureClip()
    val track =
      Track(
        listOf(
          Clip(source, TimeRange.of(200.milliseconds, 950.milliseconds)),
          Clip(source, TimeRange.of(1_000.milliseconds, 1_500.milliseconds)),
        ),
      )
    return Composition(listOf(track.withAudioEffects(listOfNotNull(trackEffect))))
  }

  private suspend fun planOf(edit: Composition): Media3PreviewPlan =
    withContext(Dispatchers.Default) {
      Media3PreviewPlanner(COMPONENTS).plan(edit, PreviewQualityPolicy.Full, ExportSpec())
    }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

  private suspend fun heard(plan: Media3PreviewPlan): HeardAudio =
    withContext(Dispatchers.IO) { playedThrough(plan.resolved.toMedia3Preview(plan.bounds).composition) }

  // The tone's level over [length] of what was heard from [from].
  private fun HeardAudio.toneOver(
    from: Duration,
    length: Duration,
  ): Float =
    ToneAnalysis.amplitudeOver(
      samples = samples,
      from = (from.inWholeMicroseconds * sampleRate / MICROS_PER_SECOND).toInt(),
      length = (length.inWholeMicroseconds * sampleRate / MICROS_PER_SECOND).toInt(),
      frequencyHz = TimelineBursts.TONE_HZ,
      sampleRate = sampleRate,
    )

  // Read over the middle of each burst and of the silence after it. media3's limit: a preview opens a trimmed clip on
  // the first access unit at or after its trim start, so what is heard can sit that far ahead of the timeline.
  private fun HeardAudio.shouldHearBursts(level: Float) {
    (0..2).forEach { index ->
      val opens = TimelineBursts.burstStart(index)
      withClue("burst $index, opening at $opens") {
        toneOver(opens + EDGE, TimelineBursts.LENGTH - EDGE * 2) shouldBe (level plusOrMinus level * LEVEL_TOLERANCE)
      }
      withClue("the silence after burst $index") {
        val silence = TimelineBursts.PERIOD - TimelineBursts.LENGTH - EDGE * 2
        toneOver(opens + TimelineBursts.LENGTH + EDGE, silence) shouldBeLessThan level * SILENT
      }
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val EDGE: Duration = 25.milliseconds
    const val LOUD = 0.5f
    const val QUIET = 0.2f
    const val LEVEL_TOLERANCE = 0.15f
    const val SILENT = 0.05f
    val MICROS_PER_SECOND = 1.seconds.inWholeMicroseconds

    val COMPONENTS: ComponentRegistry =
      ComponentRegistry
        .Builder(CONTRACT_COMPONENTS)
        .addAudioEffectResolver(
          AudioEffectResolver { spec, _ ->
            (spec as? TimelineBursts)?.let { AudioEffectResolution.Resolved(PlatformAudioEffect(it::newKernel)) }
          },
        ).build()
  }
}
