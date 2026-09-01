@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// [BrowserAudioMix.schedule] is the part of the mixer that touches no decoded media, so it is the
// part a test can reach without mediabunny or a wired-up pipeline. It runs in real headless Chrome,
// so the OfflineAudioContext and the rendered samples here are the real ones.
@OptIn(InternalFilmstripApi::class)
class BrowserAudioMixTest {
  @Test
  fun gainScalesTheSignal() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val buffer = context.createBuffer(1, HALF_SECOND, SAMPLE_RATE)
      buffer.copyToChannel(FloatArray(HALF_SECOND) { 1f }.toFloat32Array(), 0, 0)

      BrowserAudioMix.schedule(context, buffer, gain = 0.5f, offsetSeconds = 0.0, looping = false)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(0.5f, samples.at(HALF_SECOND / 2))
    }

  @Test
  fun offsetLeavesSilenceBeforeAndSignalAfter() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val buffer = context.createBuffer(1, QUARTER_SECOND, SAMPLE_RATE)
      buffer.copyToChannel(FloatArray(QUARTER_SECOND) { 1f }.toFloat32Array(), 0, 0)

      BrowserAudioMix.schedule(context, buffer, gain = 1f, offsetSeconds = 0.25, looping = false)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(0f, samples.at(QUARTER_SECOND / 2))
      assertNear(1f, samples.at(QUARTER_SECOND + QUARTER_SECOND / 2))
    }

  @Test
  fun overlappingBuffersSumAtSharedGain() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val bufferA = context.createBuffer(1, HALF_SECOND, SAMPLE_RATE)
      bufferA.copyToChannel(FloatArray(HALF_SECOND) { 1f }.toFloat32Array(), 0, 0)
      val bufferB = context.createBuffer(1, HALF_SECOND, SAMPLE_RATE)
      bufferB.copyToChannel(FloatArray(HALF_SECOND) { 1f }.toFloat32Array(), 0, 0)

      BrowserAudioMix.schedule(context, bufferA, gain = 0.5f, offsetSeconds = 0.0, looping = false)
      BrowserAudioMix.schedule(context, bufferB, gain = 0.5f, offsetSeconds = 0.0, looping = false)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(1f, samples.at(HALF_SECOND / 2))
    }

  @Test
  fun loopingBufferOutlastsItsOwnLength() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val short = context.createBuffer(1, TENTH_SECOND, SAMPLE_RATE)
      short.copyToChannel(FloatArray(TENTH_SECOND) { 1f }.toFloat32Array(), 0, 0)

      BrowserAudioMix.schedule(context, short, gain = 1f, offsetSeconds = 0.0, looping = true)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(1f, samples.at(SAMPLE_RATE.toInt() - TENTH_SECOND))
    }

  // Rendering a window at a time is what makes an hour cost the same as a minute. If this ever
  // starts tracking the timeline again, the ceiling is back.
  @Test
  fun aLongerTimelineCostsNoMoreThanAShortOne() {
    val hour = BrowserAudioMix.peakBytes(listOf(trackOf(60.minutes)), OUTPUT, 60.minutes)
    val minute = BrowserAudioMix.peakBytes(listOf(trackOf(1.minutes)), OUTPUT, 1.minutes)

    assertEquals(minute, hour)
    assertTrue(hour < BrowserAudioMix.MAX_MIX_BYTES, "one window of an hour cost $hour")
  }

  // A looping clip is decoded whole and held for the run, so unlike every other clip its own length
  // is what it costs.
  @Test
  fun aLoopingClipCostsItsWholeLength() {
    val short = BrowserAudioMix.peakBytes(listOf(trackOf(5.seconds, looping = true)), OUTPUT, 60.minutes)
    val long = BrowserAudioMix.peakBytes(listOf(trackOf(20.minutes, looping = true)), OUTPUT, 60.minutes)

    assertTrue(long > short * 2, "a 20 minute loop cost $long against a 5 second loop at $short")
  }

  private fun trackOf(
    duration: Duration,
    looping: Boolean = false,
  ): ResolvedTrack =
    ResolvedTrack(
      content = TrackContent.AudioAndVideo,
      looping = looping,
      start = Duration.ZERO,
      clips =
        listOf(
          ResolvedClip(
            source = MediaSource.Bytes(ByteArray(1)),
            info =
              MediaInfo(
                duration = duration,
                video = null,
                audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
                isExportable = true,
              ),
            start = Duration.ZERO,
            end = duration,
            effects = emptyList(),
            gain = 1f,
            startsAtKeyFrame = true,
            span = TimeRange.of(Duration.ZERO, duration),
          ),
        ),
    )

  private fun assertNear(
    expected: Float,
    actual: Float,
    epsilon: Float = EPSILON,
  ) = assertTrue(abs(expected - actual) < epsilon, "expected $expected, was $actual")

  private companion object {
    val OUTPUT = AudioFormat(sampleRate = 48_000, channelCount = 2)
    const val SAMPLE_RATE = 8_000f
    const val HALF_SECOND = 4_000
    const val QUARTER_SECOND = 2_000
    const val TENTH_SECOND = 800
    const val EPSILON = 0.01f
  }
}
