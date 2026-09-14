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
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

      BrowserAudioMix.schedule(context, buffer, gain = flat(0.5f), offsetSeconds = 0.0)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(0.5f, samples.at(HALF_SECOND / 2))
    }

  @Test
  fun offsetLeavesSilenceBeforeAndSignalAfter() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val buffer = context.createBuffer(1, QUARTER_SECOND, SAMPLE_RATE)
      buffer.copyToChannel(FloatArray(QUARTER_SECOND) { 1f }.toFloat32Array(), 0, 0)

      BrowserAudioMix.schedule(context, buffer, gain = flat(1f), offsetSeconds = 0.25)

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

      BrowserAudioMix.schedule(context, bufferA, gain = flat(0.5f), offsetSeconds = 0.0)
      BrowserAudioMix.schedule(context, bufferB, gain = flat(0.5f), offsetSeconds = 0.0)

      val samples = context.startRendering().await().getChannelData(0)
      assertNear(1f, samples.at(HALF_SECOND / 2))
    }

  // A curve that ramps is scheduled as automation rather than written once, so the samples have to
  // follow it through the middle of its run. Only its two ends would agree with a flat gain read
  // off either end of the same curve.
  @Test
  fun aRampingGainFollowsItsCurve() =
    runTest {
      val context = OfflineAudioContext(1, SAMPLE_RATE.toInt(), SAMPLE_RATE)
      val buffer = context.createBuffer(1, HALF_SECOND, SAMPLE_RATE)
      buffer.copyToChannel(FloatArray(HALF_SECOND) { 1f }.toFloat32Array(), 0, 0)
      val ramp = ResolvedGain(listOf(GainSegment(Duration.ZERO, HALF_SECOND_SPAN, 0.2f, 1f)))

      BrowserAudioMix.schedule(context, buffer, gain = ramp, offsetSeconds = 0.0)

      val samples = context.startRendering().await().getChannelData(0)
      listOf(0.25, 0.5, 0.75).forEach { fraction ->
        assertNear(ramp.gainAt(HALF_SECOND_SPAN * fraction), samples.at((HALF_SECOND * fraction).toInt()))
      }
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

  // Every pass of a looping clip asks the decode cache for the same slice, so a bed laid a thousand
  // times costs a window no more than one laid a handful of times. Counting the passes instead
  // would refuse a real loop long before it ran out of memory.
  @Test
  fun theRepeatedPassesOfALoopingClipCostOneBufferBetweenThem() {
    val few = BrowserAudioMix.peakBytes(listOf(trackOf(3.seconds, looping = true, passes = 4)), OUTPUT, 60.minutes)
    val many =
      BrowserAudioMix.peakBytes(listOf(trackOf(3.seconds, looping = true, passes = 1_200)), OUTPUT, 60.minutes)

    assertEquals(few, many)
    assertTrue(many < BrowserAudioMix.MAX_MIX_BYTES, "1200 passes of a three second bed cost $many")
  }

  // Nothing here repeats, so every clip holds a buffer of its own and there is no shared one for a
  // window's edge to cut a second copy off. Charging an edge allowance per clip instead would
  // report three times what a plain composition actually holds, and refuse at a third of the real
  // ceiling. The expected figure comes from the same function over one clip at a time.
  @Test
  fun aCompositionThatRepeatsNothingCostsOneBufferPerClip() {
    val lengths = listOf(2.seconds, 5.seconds, 11.seconds)
    val total = lengths.fold(Duration.ZERO, Duration::plus)
    val output = BrowserAudioMix.peakBytes(emptyList(), OUTPUT, total)
    val expected = output + lengths.sumOf { BrowserAudioMix.peakBytes(listOf(trackOf(it)), OUTPUT, total) - output }

    assertEquals(expected, BrowserAudioMix.peakBytes(listOf(trackOf(lengths)), OUTPUT, total))
  }

  // One source read by several tracks is one buffer in the cache, since every track asks for the
  // same trim, and only the pass each window edge cuts is a track's own.
  @Test
  fun tracksSharingOneTrimShareItsBuffer() {
    val bed = trackOf(3.seconds, looping = true, passes = 4)
    val one = BrowserAudioMix.peakBytes(listOf(bed), OUTPUT, 60.minutes)
    val output = BrowserAudioMix.peakBytes(emptyList(), OUTPUT, 60.minutes)
    val four = BrowserAudioMix.peakBytes(List(4) { bed }, OUTPUT, 60.minutes)

    assertTrue(four - output < (one - output) * 4, "four tracks over one trim cost ${four - output}")
  }

  // Every pass sounds at the slot the planner laid it at, read off the plan rather than worked out
  // again here, so a plan that moved a pass moves the mix with it.
  @Test
  fun aLoopingTrackPlacesEveryPassWhereThePlanLaidIt() {
    val track = trackOf(1_700.milliseconds, looping = true, passes = 4, start = 700.milliseconds)

    val placed = BrowserAudioMix.placed(listOf(track))

    assertEquals(track.clips.size, placed.size)
    placed.forEachIndexed { index, one ->
      assertEquals(track.clips[index].span.start, one.offset, "pass $index opened at ${one.offset}")
    }
  }

  // The span a flat curve covers never reaches the graph, since a constant is one write on the gain
  // parameter rather than any automation.
  private fun flat(gain: Float): ResolvedGain = ResolvedGain.constant(gain, Duration.ZERO, HALF_SECOND_SPAN)

  /**
   * A track carrying [passes] copies of one clip, laid end to end the way the planner lays a
   * looping track.
   */
  private fun trackOf(
    duration: Duration,
    looping: Boolean = false,
    passes: Int = 1,
    start: Duration = Duration.ZERO,
  ): ResolvedTrack = trackOf(List(passes) { duration }, looping, start, repeating = true)

  /**
   * A track carrying one clip of each of [lengths], which for distinct lengths repeats nothing.
   */
  private fun trackOf(lengths: List<Duration>): ResolvedTrack = trackOf(lengths, repeating = false)

  private fun trackOf(
    lengths: List<Duration>,
    looping: Boolean = false,
    start: Duration = Duration.ZERO,
    repeating: Boolean = false,
  ): ResolvedTrack {
    val source = MediaSource.Bytes(ByteArray(1))
    var offset = start
    return ResolvedTrack(
      content = TrackContent.AudioAndVideo,
      looping = looping,
      start = start,
      clips =
        lengths.mapIndexed { index, duration ->
          ResolvedClip(
            source = source,
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
            gain = flat(1f),
            startsAtKeyFrame = true,
            span = TimeRange.of(offset, offset + duration).also { offset += duration },
            sourceIndex = if (repeating) 0 else index,
          )
        },
    )
  }

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
    val HALF_SECOND_SPAN = 500.milliseconds
    const val EPSILON = 0.01f
  }
}
