@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.avfoundation.AudioProbe
import dev.jordond.filmstrip.avfoundation.Delay
import dev.jordond.filmstrip.avfoundation.DelayKernel
import dev.jordond.filmstrip.avfoundation.PROBE_SAMPLE_RATE
import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.mixedAudioOf
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What an [AvKernelTap] does to a composition track's audio, with the mix read through a reader so the samples can be
 * held to the frame.
 *
 * [TimelineBursts] replaces the track's sound with bursts on the composition timeline, so where a burst is read says
 * which position the tap handed its kernels and how loud it is says what ran after the tap.
 */
class AvKernelTapMediaTest {
  @Test
  fun aTapLaysItsBurstsOnTheCompositionTimeline() {
    val edit = edit(at = Duration.ZERO)

    val audio = edit.read(TapSpan(TimeRange.of(Duration.ZERO, LENGTH), bursts()))

    audio.shouldHearBursts(0..2, LOUD)
  }

  @Test
  fun aSpanRunsOnlyInsideItsRangeAndTheRestPassesThrough() {
    val edit = edit(at = Duration.ZERO)

    val audio = edit.read(TapSpan(TimeRange.of(TimelineBursts.PERIOD, TimelineBursts.PERIOD * 2), bursts()))

    withClue("the fixture's own tone ahead of the span") { audio.tone(100.milliseconds, FIXTURE_HZ) shouldBe FIXTURE }
    audio.shouldHearBursts(1..1, LOUD)
    withClue("the fixture's own tone after the span") { audio.tone(900.milliseconds, FIXTURE_HZ) shouldBe FIXTURE }
  }

  @Test
  fun aTapRunsAheadOfTheTracksVolume() {
    val edit = edit(at = Duration.ZERO)

    val audio = edit.read(TapSpan(TimeRange.of(Duration.ZERO, LENGTH), bursts()), volume = QUARTER)

    audio.shouldHearBursts(0..2, LOUD * QUARTER)
  }

  // A tap is handed nothing ahead of the block, so it cannot take the latency out. Every block still comes back
  // whole, and the track plays late by exactly what the kernels hold.
  @Test
  fun aTapPlaysLateByItsKernelsLatency() {
    val plain = edit(at = Duration.ZERO).read(TapSpan(TimeRange.of(Duration.ZERO, LENGTH), bursts()))

    val audio =
      edit(
        at = Duration.ZERO,
      ).read(AvKernelTap(listOf(TapSpan(TimeRange.of(Duration.ZERO, LENGTH), delayed()))))

    audio.samples.size shouldBe plain.samples.size
    audio.onsets().drop(1) shouldBe plain.onsets().drop(1).map { it + LATENCY_FRAMES }
  }

  // The track's audio starts a second in, so the first bursts fall where the track has nothing. AVFoundation hands
  // the tap silence there, and an effect that makes sound of its own is heard through the gap.
  @Test
  fun aTapRunsAcrossAnEmptyEdit() {
    val edit = edit(at = 1.seconds)

    val span = TapSpan(TimeRange.of(Duration.ZERO, 1.seconds + LENGTH), bursts())

    val audio = edit.read(AvKernelTap(listOf(span)))

    audio.shouldHearBursts(0..4, LOUD)
  }

  @Test
  fun aSwapAheadOfTheReadIsWhatTheTapRuns() {
    val edit = edit(at = Duration.ZERO)
    val range = TimeRange.of(Duration.ZERO, LENGTH)
    val tap = AvKernelTap(listOf(TapSpan(range, bursts())))

    tap.swap(listOf(TapSpan(range, bursts(QUARTER))))

    edit.read(tap).shouldHearBursts(0..2, QUARTER)
  }

  // A plan resolves its effects again on every change, so a swap is handed new objects for effects that did not
  // change. A prepared tap keeps the kernels it has for those, and what they hold.
  @Test
  fun aSwapToTheEffectsASpanAlreadyRunsKeepsItsKernels() {
    var built = 0

    fun span(amount: Float) =
      TapSpan(
        TimeRange.of(Duration.ZERO, LENGTH),
        listOf(
          ResolvedAudioEffect(
            EffectId("delay"),
            PlatformAudioEffect {
              built++
              DelayKernel(it, LATENCY_FRAMES)
            },
            Delay((amount * LATENCY_FRAMES).toInt()),
          ),
        ),
      )
    val tap = AvKernelTap(listOf(span(0.5f)))
    memScoped {
      val format =
        alloc<AudioStreamBasicDescription>().apply {
          mSampleRate = PROBE_SAMPLE_RATE.toDouble()
          mFormatID = kAudioFormatLinearPCM
          mFormatFlags = kAudioFormatFlagIsFloat or kAudioFormatFlagIsNonInterleaved
          mChannelsPerFrame = 2u
          mBitsPerChannel = 32u
        }
      tap.prepare(maxFrames = 4_096, format = format)
    }

    tap.swap(listOf(span(0.5f)))
    val afterTheSame = built
    tap.swap(listOf(span(0.6f)))
    tap.unprepare()

    afterTheSame shouldBe 1
    built shouldBe 2
  }

  private class Edit(
    val composition: AVMutableComposition,
    val track: AVMutableCompositionTrack,
  ) {
    fun read(
      span: TapSpan,
      volume: Float = 1f,
    ): AudioProbe = read(AvKernelTap(listOf(span)), volume)

    fun read(
      tap: AvKernelTap,
      volume: Float = 1f,
    ): AudioProbe {
      val reference = checkNotNull(kernelTapOf(tap)) { "MediaToolbox built no tap" }
      val parameters =
        AVMutableAudioMixInputParameters().apply {
          setTrackID(track.trackID)
          setVolume(volume, atTime = Duration.ZERO.toCMTime())
          setAudioTapProcessor(reference)
        }
      CFRelease(reference)
      val mix = AVMutableAudioMix().apply { setInputParameters(listOf(parameters)) }
      val audio = mixedAudioOf(composition, mix)
      withClue("the tap was never run") { (tap.blocks > 0) shouldBe true }
      return audio
    }
  }

  // The fixture's tone from [TRIM_START] for [LENGTH], laid on one composition track at [at].
  private fun edit(at: Duration): Edit {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(fixture(TONE)), options = null)
    val source = asset.tracksWithMediaType(AVMediaTypeAudio).first() as AVAssetTrack
    val composition = AVMutableComposition()
    val track =
      checkNotNull(composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid))
    track.insertTimeRange(timeRangeOf(TRIM_START, LENGTH), ofTrack = source, atTime = at.toCMTime(), error = null)
    return Edit(composition, track)
  }

  private fun delayed() =
    bursts() + ResolvedAudioEffect(EffectId("delay"), PlatformAudioEffect { DelayKernel(it, LATENCY_FRAMES) })

  // The frame each run of sound opens on, a run being closed by a stretch of silence.
  private fun AudioProbe.onsets(): List<Int> {
    val onsets = mutableListOf<Int>()
    var quiet = QUIET_RUN
    samples.forEachIndexed { index, sample ->
      if (abs(sample) > AUDIBLE) {
        if (quiet >= QUIET_RUN) onsets += index
        quiet = 0
      } else {
        quiet++
      }
    }
    return onsets
  }

  private fun bursts(level: Float = LOUD) =
    listOf(ResolvedAudioEffect(TimelineBursts.ID, PlatformAudioEffect(TimelineBursts(level)::newKernel)))

  private fun AudioProbe.tone(
    at: Duration,
    frequencyHz: Int,
  ): Float = toneOver(at, WINDOW, frequencyHz)

  private fun AudioProbe.burst(index: Int): Float =
    tone(TimelineBursts.burstStart(index) + EDGE, TimelineBursts.TONE_HZ)

  private fun AudioProbe.shouldHearBursts(
    bursts: IntRange,
    level: Float,
  ) {
    bursts.forEach { index ->
      withClue("burst $index, opening at ${TimelineBursts.burstStart(index)}") {
        burst(index) shouldBe (level plusOrMinus level * LEVEL_TOLERANCE)
      }
      withClue("the silence after burst $index") {
        tone(TimelineBursts.burstStart(index) + TimelineBursts.LENGTH + EDGE, TimelineBursts.TONE_HZ) shouldBeLessThan
          level * SILENT
      }
    }
  }

  private companion object {
    const val TONE = "tone_bed_880.mp4"
    const val FIXTURE_HZ = 880
    val FIXTURE = 0.5f plusOrMinus 0.45f
    val TRIM_START = 200.milliseconds
    val LENGTH = 1_200.milliseconds
    val EDGE: Duration = 20.milliseconds
    val WINDOW: Duration = 40.milliseconds
    const val LOUD = 0.5f
    const val QUARTER = 0.25f
    const val LATENCY_FRAMES = 480
    const val AUDIBLE = 0.05f
    const val QUIET_RUN = 1_000
    const val LEVEL_TOLERANCE = 0.05f
    const val SILENT = 0.05f
  }
}
