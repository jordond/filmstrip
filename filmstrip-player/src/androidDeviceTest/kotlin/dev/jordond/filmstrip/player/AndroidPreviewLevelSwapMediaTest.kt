package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * A level or a fade handed to a standing media3 preview, read in what `CompositionPlayer` hands its audio sink.
 *
 * [TestTones] runs on the track ahead of its level, so the level is all that moves a tone of a known size, and what is
 * heard is held to the gain the plan resolved, the curve an export of the same edit is scaled by.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidPreviewLevelSwapMediaTest {
  @Test
  fun aChangedVolumeIsHeardOnTheStandingGraph() =
    runTest(timeout = TIMEOUT) {
      val standing = planOf(edit())
      val preview = standing.resolved.toMedia3Preview(standing.bounds)
      val next = planOf(edit(level = AudioLevel.Volume(QUIET)))

      preview.updateParameters(next.resolved, next.bounds) shouldBe true

      val heard = withContext(Dispatchers.IO) { playedThrough(preview.composition) }
      heard.shouldPlay(next.gain(), listOf(750.milliseconds), window = 600.milliseconds)
    }

  // Read part way up the fade in and part way down the fade out, where a ramp of the wrong length or shape shows.
  @Test
  fun aChangedFadeIsHeardThroughItsMiddleOnTheStandingGraph() =
    runTest(timeout = TIMEOUT) {
      val standing = planOf(edit())
      val preview = standing.resolved.toMedia3Preview(standing.bounds)
      val next = planOf(edit(fadeIn = FADE, fadeOut = FADE))

      preview.updateParameters(next.resolved, next.bounds) shouldBe true

      val heard = withContext(Dispatchers.IO) { playedThrough(preview.composition) }
      val rising = listOf(100, 200, 300).map { it.milliseconds }
      val falling = rising.map { LENGTH - it }
      heard.shouldPlay(next.gain(), rising + (LENGTH / 2) + falling, window = 40.milliseconds)
    }

  // The cue swaps the level in from the audio thread once the stream reaches it, so the change lands while the player
  // is part way through the clip and at a known place in it.
  @Test
  fun aVolumeChangedWhileTheClipPlaysIsHeardFromWhereTheStreamHasReached() =
    runTest(timeout = TIMEOUT) {
      val cue = SwapCue(CUE_AT)
      val standing = planOf(edit(cue = cue))
      val preview = standing.resolved.toMedia3Preview(standing.bounds)
      val next = planOf(edit(level = AudioLevel.Volume(QUIET), cue = cue))
      val swapped = AtomicReference<Boolean?>(null)
      cue.onReached = { swapped.set(preview.updateParameters(next.resolved, next.bounds)) }

      val heard = withContext(Dispatchers.IO) { playedThrough(preview.composition) }

      swapped.get() shouldBe true
      heard.shouldPlay(standing.gain(), listOf(300.milliseconds), window = 200.milliseconds)
      heard.shouldPlay(next.gain(), listOf(1_100.milliseconds), window = 400.milliseconds)
    }

  private fun edit(
    level: AudioLevel = AudioLevel.Inherit,
    fadeIn: Duration = Duration.ZERO,
    fadeOut: Duration = Duration.ZERO,
    cue: SwapCue? = null,
  ): Composition {
    val clip = Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, LENGTH))
    val tones = TestTones(listOf(TONE_HZ), TONE, LENGTH)
    return Composition(
      listOf(
        Track(
          listOf(clip),
          audio = level,
          fadeIn = fadeIn,
          fadeOut = fadeOut,
          audioEffects = listOfNotNull(tones, cue),
        ),
      ),
    )
  }

  private suspend fun planOf(edit: Composition): Media3PreviewPlan =
    withContext(Dispatchers.Default) {
      Media3PreviewPlanner(COMPONENTS).plan(edit, PreviewQualityPolicy.Full, ExportSpec())
    }.shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>().plan

  // The one clip's resolved gain, in its own time, which is the timeline's since the clip opens it.
  private fun Media3PreviewPlan.gain(): ResolvedGain =
    resolved.tracks
      .first()
      .clips
      .first()
      .gain

  // The tone over [window] around each of [at], against the tone the curve leaves there.
  private fun HeardAudio.shouldPlay(
    gain: ResolvedGain,
    at: List<Duration>,
    window: Duration,
  ) {
    at.forEach { middle ->
      val heard =
        ToneAnalysis.amplitudeOver(
          samples = samples,
          from = frames(middle - window / 2),
          length = frames(window),
          frequencyHz = TONE_HZ,
          sampleRate = sampleRate,
        )
      withClue("the tone around $middle") { heard shouldBe (TONE * gain.gainAt(middle) plusOrMinus TONE * TOLERANCE) }
    }
  }

  private fun HeardAudio.frames(time: Duration): Int = (time.toDouble(DurationUnit.SECONDS) * sampleRate).toInt()

  /**
   * An audio effect that changes nothing and calls [onReached] once, from the audio thread, when its stream gets to
   * [at] on the timeline.
   */
  private class SwapCue(
    val at: Duration,
  ) : AudioEffectSpec {
    @Volatile
    var onReached: (() -> Unit)? = null

    override val id: EffectId get() = EffectId("test.swap-cue")

    fun newKernel(format: AudioFormat): AudioKernel {
      val cueFrame = (at.toDouble(DurationUnit.SECONDS) * format.sampleRate).toLong()
      return object : AudioKernel() {
        override fun process(
          buffer: FloatArray,
          frames: Int,
          position: Long,
        ) {
          if (position + frames <= cueFrame) return
          onReached?.also { onReached = null }?.invoke()
        }
      }
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val LENGTH = 1_500.milliseconds
    val FADE = 400.milliseconds
    val CUE_AT = 700.milliseconds
    const val TONE_HZ = 1_000
    const val TONE = 0.5f
    const val QUIET = 0.4f

    // media3's limit: a preview opens a clip on an access unit, so what is heard can sit a few milliseconds off the
    // timeline, which a fade turns into a level.
    const val TOLERANCE = 0.1f

    val COMPONENTS: ComponentRegistry =
      ComponentRegistry
        .Builder(CONTRACT_COMPONENTS)
        .addAudioEffectResolver(
          AudioEffectResolver { spec, _ ->
            when (spec) {
              is TestTones -> AudioEffectResolution.Resolved(PlatformAudioEffect(spec::newKernel))
              is SwapCue -> AudioEffectResolution.Resolved(PlatformAudioEffect(spec::newKernel))
              else -> null
            }
          },
        ).build()
  }
}
