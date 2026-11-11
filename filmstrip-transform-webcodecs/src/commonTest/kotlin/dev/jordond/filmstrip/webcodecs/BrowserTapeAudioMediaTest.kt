@file:OptIn(ExperimentalWasmJsInterop::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.effects.filters.FilterAudioEffectResolver
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioParams
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.PitchBend
import dev.jordond.filmstrip.test.TestTones
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A [TapeAudio] in the browser's export mix, read as samples and held to [tapeAudioParams].
 *
 * [TestTones] runs ahead of it, so the tape is handed tones whose level and pitch are known. Nothing is encoded, so
 * what is read is what the kernel wrote.
 */
class BrowserTapeAudioMediaTest {
  @Test
  fun aSteadyTapeNarrowsTheBandToItsCornersAndLaysItsHissUnderTheSound() =
    runTest {
      val tape = TapeAudio(wow = Wobble(0f), flutter = Wobble(0f))
      val params = tapeAudioParams(tape, RATE)
      val corner = params.lowPassHz.toInt()
      val tones = TestTones(listOf(100, 1_000, corner, corner * 2), CHORD, TONES_END)

      val samples = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = listOf(tones(tones), tape(tape)))

      val middle = samples.tone(1_000)
      middle shouldBe (CHORD plusOrMinus CHORD * TOLERANCE)
      samples.tone(params.highPassHz.toInt()) / middle shouldBe (CORNER plusOrMinus TOLERANCE)
      samples.tone(corner) / middle shouldBe (CORNER plusOrMinus TOLERANCE)
      samples.tone(corner * 2) / middle shouldBeLessThan STOPPED
      samples.hiss() shouldBe (params.hissRms.toFloat() plusOrMinus params.hissRms.toFloat() * HISS_TOLERANCE)
    }

  @Test
  fun theDefaultTapeBendsAToneByTheWowAndTheFlutterTheParamsGive() =
    runTest {
      val tape = TapeAudio()
      val params = tapeAudioParams(tape, RATE)
      val tones = TestTones(listOf(CARRIER), TONE, WHOLE)

      val samples = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = listOf(tones(tones), tape(tape)))

      val bend = samples.bend(cycles(2, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      bend.depthAt(params.flutterRateHz) shouldBe (params.flutterDepth plusOrMinus params.flutterDepth * BEND_TOLERANCE)
    }

  @Test
  fun twoMixesOfOneTapeAreTheSameSamples() =
    runTest {
      val effects = { listOf(tones(TestTones(listOf(CARRIER), TONE, TONES_END)), tape(TapeAudio())) }

      val first = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = effects())
      val second = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = effects())

      first.toList() shouldBe second.toList()
    }

  // A window boundary is where a kernel that started over would lose its place on the tape.
  @Test
  fun theTapeIsTheSameAcrossShortWindows() =
    runTest {
      val effects = { listOf(tones(TestTones(listOf(CARRIER), TONE, TONES_END)), tape(TapeAudio())) }

      val whole = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = effects())
      val windowed = mix(listOf(clipOf(toneSource(), WHOLE)), mixEffects = effects(), window = SHORT_WINDOW)

      windowed.toList() shouldBe whole.toList()
    }

  @Test
  fun aClipsTapeRunsAfterItsSpeedAndAheadOfItsLevel() =
    runTest {
      val tape = TapeAudio(flutter = Wobble(0f))
      val params = tapeAudioParams(tape, RATE)
      val effects = listOf(tones(TestTones(listOf(CARRIER), TONE, TONES_END)), tape(tape))
      val clip = clipOf(toneSource(), WHOLE / 2, speed = HALF_SPEED, gain = CLIP_GAIN, effects = effects)

      val samples = mix(listOf(clip))

      samples.tone(CARRIER) shouldBe (TONE * CLIP_GAIN plusOrMinus TONE * CLIP_GAIN * TOLERANCE)
      val bend = samples.bend(cycles(1, params.wowRateHz))
      bend.depthAt(params.wowRateHz) shouldBe (params.wowDepth plusOrMinus params.wowDepth * BEND_TOLERANCE)
      val hiss = params.hissRms.toFloat() * CLIP_GAIN
      samples.hiss() shouldBe (hiss plusOrMinus hiss * HISS_TOLERANCE)
    }

  private suspend fun mix(
    clips: List<ResolvedClip>,
    mixEffects: List<ResolvedAudioEffect> = emptyList(),
    window: Duration = BrowserAudioMix.WINDOW,
  ): FloatArray {
    val sources = SourceCache()
    try {
      val joined = FloatArray(frames(WHOLE))
      var at = 0
      BrowserAudioMix.mixInto(
        tracks =
          listOf(
            ResolvedTrack(TrackContent.AudioAndVideo, looping = false, start = Duration.ZERO, clips = clips),
          ),
        format = AudioFormat(sampleRate = RATE, channelCount = 1),
        duration = WHOLE,
        sources = sources,
        window = window,
        mixEffects = mixEffects,
      ) { buffer ->
        val samples = buffer.getChannelData(0)
        for (index in 0 until buffer.length) joined[at + index] = samples.at(index)
        at += buffer.length
      }
      at shouldBe joined.size
      return joined
    } finally {
      sources.close()
    }
  }

  private suspend fun toneSource(): MediaSource =
    MediaSource.Bytes(makeClipWithAudio(frames = frames(WHOLE, FIXTURE_RATE), frameRate = FIXTURE_RATE))

  private fun clipOf(
    source: MediaSource,
    length: Duration,
    speed: Double = 1.0,
    gain: Float = 1f,
    effects: List<ResolvedAudioEffect> = emptyList(),
  ): ResolvedClip {
    val played = outputLengthOf(length, speed)
    return ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = WHOLE,
          video = null,
          audioTracks =
            listOf(AudioTrackInfo(CLIP_AUDIO_TRACK, trackCodecOf("opus"), RATE, 1, null, null, isDefault = true)),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(gain, Duration.ZERO, played),
      startsAtKeyFrame = true,
      span = TimeRange.of(Duration.ZERO, played),
      sourceIndex = 0,
      speed = speed,
      audioEffects = effects,
    )
  }

  private fun tones(tones: TestTones): ResolvedAudioEffect =
    ResolvedAudioEffect(TestTones.ID, PlatformAudioEffect(tones::newKernel), tones)

  private fun tape(tape: TapeAudio): ResolvedAudioEffect {
    val attributes = AudioAttributes(TimeRange.of(Duration.ZERO, WHOLE))
    val resolved =
      FilterAudioEffectResolver().resolve(tape, attributes).shouldBeInstanceOf<AudioEffectResolution.Resolved>()
    return ResolvedAudioEffect(TapeAudio.ID, resolved.effect, tape)
  }

  private fun FloatArray.tone(hz: Int): Float =
    ToneAnalysis.levelOver(this, frames(TONE_FROM), frames(TONE_READ), hz, RATE)

  private fun FloatArray.hiss(): Float = ToneAnalysis.rmsOver(this, frames(HISS_FROM), frames(HISS_READ))

  private fun FloatArray.bend(length: Duration): PitchBend =
    PitchBend.of(this, frames(BEND_FROM), frames(length), CARRIER, RATE)

  private fun frames(
    time: Duration,
    rate: Int = RATE,
  ): Int = (time.toDouble(DurationUnit.SECONDS) * rate).roundToInt()

  private fun cycles(
    count: Int,
    rateHz: Double,
  ): Duration = (count / rateHz).seconds

  private companion object {
    const val RATE = 48_000
    const val FIXTURE_RATE = 30
    const val CHORD = 0.1f
    const val TONE = 0.4f
    const val CARRIER = 1_000
    const val CLIP_GAIN = 0.5f
    const val HALF_SPEED = 0.5
    const val CORNER = 0.7071f
    const val TOLERANCE = 0.03f
    const val HISS_TOLERANCE = 0.05f
    const val STOPPED = 0.08f
    const val BEND_TOLERANCE = 0.05
    val WHOLE = 4.seconds
    val TONES_END = 2.seconds
    val TONE_FROM = 500.milliseconds
    val TONE_READ = 1.seconds
    val HISS_FROM = 2_500.milliseconds
    val HISS_READ = 1.seconds
    val BEND_FROM = 250.milliseconds
    val SHORT_WINDOW = 200.milliseconds
  }
}
