@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.audio.DecodeCache
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What the export mix writes for a clip that plays at another speed, read in output time against the shared functions.
 *
 * The level is read as RMS and never as a peak, since a stretch overlapping grains is free to move a single tone's
 * peak and the other backends do the same.
 */
class BrowserSpeedMixTest {
  @Test
  fun aClipKeepsItsPitchLengthAndLevelAtEverySpeed() =
    runTest {
      RANGE.forEach { speed ->
        val trim = sourceLengthFor(speed)
        val bytes = makeClipWithAudio(frames = framesOf(trim), frameRate = FIXTURE_RATE)
        val source = MediaSource.Bytes(bytes)
        val unity = mix(listOf(clipOf(source, Duration.ZERO, trim, 1.0)), trim)
        val clip = clipOf(source, Duration.ZERO, trim, speed)

        val mixed = mix(listOf(clip), clip.outputDuration)

        withClue("at speed $speed") {
          mixed.size shouldBe outputFramesOf(outputLengthOf(trim, speed))
          mixed.dominantHz(OUTPUT_RATE) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
          mixed.middleRms().shouldBeWithinPercentageOf(unity.middleRms(), RMS_PERCENT)
        }
      }
    }

  // The tone moves part of the way through the source, so a slice cut one to one reads the wrong tone at the middle
  // of a clip even though both ends of it agree.
  @Test
  fun aTrimmedClipReadsTheSourceTheSharedFunctionsNameAtEveryOutputTime() =
    runTest {
      val source = MediaSource.Bytes(steppedTone())
      val clip = clipOf(source, TRIM_FROM, TRIM_TO, speed = 1.5)

      val mixed = mix(listOf(clip), clip.outputDuration)

      mixed.size shouldBe outputFramesOf(outputLengthOf(clip.sourceDuration, clip.speed))
      listOf(0.2.seconds, 0.5.seconds, 0.85.seconds, 1.2.seconds).forEach { at ->
        val expected = if (clip.sourceTimeAt(at) < TONE_STEP) TONE_HZ else STEPPED_HZ
        withClue("at $at, source ${clip.sourceTimeAt(at)}") {
          mixed.dominantAround(at) shouldBe (expected plusOrMinus DOMINANT_STEP_HZ)
        }
      }
    }

  @Test
  fun aSecondClipOpensWhereTheFirstClipsOutputDurationEnds() =
    runTest {
      val source = MediaSource.Bytes(steppedTone())
      val first = clipOf(source, Duration.ZERO, 0.8.seconds, speed = 0.7)
      val second = clipOf(source, 1.2.seconds, 2.seconds, speed = 1.5, at = first.span.endInclusiveOrThrow())
      val total = first.outputDuration + second.outputDuration

      val mixed = mix(listOf(first, second), total)

      mixed.size shouldBe outputFramesOf(total)
      mixed.dominantAround(first.outputDuration / 2) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
      mixed.dominantAround(first.outputDuration + second.outputDuration / 2) shouldBe
        (STEPPED_HZ plusOrMinus DOMINANT_STEP_HZ)
    }

  // A window boundary is where a stretcher that started over would jump phase, so the fixture crosses a good many.
  @Test
  fun aSpedClipCrossesWindowsWithoutAClick() =
    runTest {
      listOf(1.0 / 32.0, 0.7, 1.5).forEach { speed ->
        val trim = if (speed < 0.1) 0.25.seconds else 2.seconds
        val source = MediaSource.Bytes(makeClipWithAudio(frames = framesOf(trim), frameRate = FIXTURE_RATE))
        val clip = clipOf(source, Duration.ZERO, trim, speed)

        val windowed = mix(listOf(clip), clip.outputDuration, window = SHORT_WINDOW)
        val whole = mix(listOf(clip), clip.outputDuration)

        withClue("at speed $speed") {
          windowed.size shouldBe whole.size
          val worst = windowed.indices.maxOf { abs(windowed[it] - whole[it]) }
          withClue("the windowed mix diverged from one pass by $worst") { (worst < MAX_DIVERGENCE) shouldBe true }
        }
      }
    }

  @Test
  fun everyWholePassOfALoopingClipAtASpeedDecodesOnce() =
    runTest {
      val source = MediaSource.Bytes(makeClipWithAudio(frames = FIXTURE_RATE, frameRate = FIXTURE_RATE))
      val length = outputLengthOf(1.seconds, MIDDLE_SPEED)
      val passes = List(LOOP_PASSES) { clipOf(source, Duration.ZERO, 1.seconds, MIDDLE_SPEED, at = length * it) }
      val decoded = DecodeCache()
      val sources = SourceCache()

      try {
        BrowserAudioMix.mixInto(
          tracks = listOf(trackOf(passes)),
          format = FORMAT,
          duration = length * LOOP_PASSES,
          sources = sources,
          decoded = decoded,
        ) {}
      } finally {
        sources.close()
      }

      decoded.decodes shouldBe 1
    }

  // A fade reads output time, so the middle of the curve has to land at the middle of the clip on the timeline.
  @Test
  fun aFadeOnASpedClipFollowsItsCurveInOutputTime() =
    runTest {
      val source = MediaSource.Bytes(makeClipWithAudio(frames = FIXTURE_RATE, frameRate = FIXTURE_RATE))
      val flat = clipOf(source, Duration.ZERO, 1.seconds, MIDDLE_SPEED)
      val length = flat.outputDuration
      val fade = ResolvedGain(listOf(GainSegment(Duration.ZERO, length, FADE_FROM, FADE_TO)))
      val faded = clipOf(source, Duration.ZERO, 1.seconds, MIDDLE_SPEED, gain = fade)

      val flatMix = mix(listOf(flat), length)
      val fadedMix = mix(listOf(faded), length)

      listOf(0.25, 0.5, 0.75).forEach { fraction ->
        val at = length * fraction
        val ratio = fadedMix.rmsAround(at) / flatMix.rmsAround(at)
        withClue("at $at") { ratio.shouldBeWithinPercentageOf(fade.gainAt(at).toDouble(), RMS_PERCENT) }
      }
    }

  // The source a stretcher reads runs through it and is not held, so going faster costs a window nothing extra.
  @Test
  fun aFasterClipCostsAWindowNoMoreThanOneAtSpeedOne() {
    val hour = 1.seconds * SECONDS_PER_HOUR
    val info = infoOf(hour)
    val unity = clipOf(MediaSource.Uri("a"), Duration.ZERO, hour, 1.0, info = info)
    val fast = clipOf(MediaSource.Uri("a"), Duration.ZERO, hour, 32.0, info = info)

    BrowserAudioMix.peakBytes(listOf(trackOf(listOf(fast))), FORMAT, fast.outputDuration) shouldBe
      BrowserAudioMix.peakBytes(listOf(trackOf(listOf(unity))), FORMAT, fast.outputDuration)
  }

  private suspend fun mix(
    clips: List<ResolvedClip>,
    duration: Duration,
    window: Duration = BrowserAudioMix.WINDOW,
  ): FloatArray {
    val sources = SourceCache()
    try {
      val joined = FloatArray(outputFramesOf(duration))
      var at = 0
      BrowserAudioMix.mixInto(
        tracks = listOf(trackOf(clips)),
        format = FORMAT,
        duration = duration,
        sources = sources,
        window = window,
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

  private fun clipOf(
    source: MediaSource,
    from: Duration,
    to: Duration,
    speed: Double,
    at: Duration = Duration.ZERO,
    gain: ResolvedGain? = null,
    info: MediaInfo = infoOf(to),
  ): ResolvedClip {
    val length = outputLengthOf(to - from, speed)
    return ResolvedClip(
      source = source,
      info = info,
      start = from,
      end = to,
      effects = emptyList(),
      gain = gain ?: ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = true,
      span = TimeRange.of(at, at + length),
      sourceIndex = 0,
      speed = speed,
    )
  }

  private fun trackOf(clips: List<ResolvedClip>): ResolvedTrack =
    ResolvedTrack(content = TrackContent.AudioAndVideo, looping = false, start = Duration.ZERO, clips = clips)

  private fun infoOf(duration: Duration): MediaInfo =
    MediaInfo(
      duration = duration,
      video = null,
      audio = AudioTrackInfo(trackCodecOf("opus"), OUTPUT_RATE, 1, null),
      isExportable = true,
    )

  private suspend fun steppedTone(): ByteArray =
    makeClipWithAudio(frames = STEPPED_FRAMES, frameRate = FIXTURE_RATE) { seconds ->
      if (seconds.seconds < TONE_STEP) TONE_HZ else STEPPED_HZ
    }

  private fun TimeRange.endInclusiveOrThrow(): Duration = start + checkNotNull(duration)

  // Enough source that the output runs long enough to read, whichever way the speed goes.
  private fun sourceLengthFor(speed: Double): Duration =
    when {
      speed < 0.1 -> 0.5.seconds
      speed > 10.0 -> 16.seconds
      else -> 2.seconds
    }

  private fun framesOf(length: Duration): Int = (length.toDouble(DurationUnit.SECONDS) * FIXTURE_RATE).roundToInt()

  private fun outputFramesOf(length: Duration): Int = (length.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()

  private fun FloatArray.middleRms(): Double = rms(size / 4, size * 3 / 4)

  private fun FloatArray.around(at: Duration): FloatArray {
    val middle = outputFramesOf(at)
    val half = outputFramesOf(READ_SPAN / 2)
    return copyOfRange((middle - half).coerceAtLeast(0), (middle + half).coerceAtMost(size))
  }

  private fun FloatArray.dominantAround(at: Duration): Double = around(at).dominantHz(OUTPUT_RATE)

  private fun FloatArray.rmsAround(at: Duration): Double = around(at).rms()

  private companion object {
    const val OUTPUT_RATE = 48_000
    const val FIXTURE_RATE = 30
    const val TONE_HZ = 440.0
    const val STEPPED_HZ = 880.0
    const val MIDDLE_SPEED = 0.7
    const val RMS_PERCENT = 15.0
    const val MAX_DIVERGENCE = 0.02f
    const val LOOP_PASSES = 3
    const val FADE_FROM = 0.2f
    const val FADE_TO = 1f
    const val STEPPED_FRAMES = 90
    const val SECONDS_PER_HOUR = 3_600
    val FORMAT = AudioFormat(sampleRate = OUTPUT_RATE, channelCount = 1)
    val RANGE = listOf(1.0 / 32.0, MIDDLE_SPEED, 1.5, 32.0)
    val TRIM_FROM = 0.5.seconds
    val TRIM_TO = 2.5.seconds
    val TONE_STEP = 1.5.seconds
    val SHORT_WINDOW = 200.milliseconds
    val READ_SPAN = 160.milliseconds
  }
}
