@file:OptIn(ExperimentalWasmJsInterop::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.webcodecs

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
import dev.jordond.filmstrip.webcodecs.BrowserMixSeamTest.Companion.MAX_STEP
import dev.jordond.filmstrip.webcodecs.internal.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.Float32Array
import dev.jordond.filmstrip.webcodecs.internal.OfflineAudioContext
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * What a mixed timeline has to look like end to end, whatever the mixer does internally to build
 * it: one unbroken tone, no gap, no jump, and exactly as many frames as the timeline asked for.
 *
 * A mixer that renders the timeline in windows has to keep passing these. A window boundary that
 * drops a frame, repeats one, or restarts a resampler shows up as a dropout or a step here, and
 * neither is visible to a test that only counts samples or measures a peak.
 */
class BrowserMixSeamTest {
  @Test
  fun aMixedToneRunsUnbroken() =
    runTest {
      val samples = mixToneOf(sampleRate = OUTPUT_RATE)

      assertEquals(expectedFrames(), samples.length)
      assertUnbroken(samples)
    }

  // The mixer resamples anything that does not already run at the output rate, which is the case a
  // window boundary is most likely to damage: a resampler that restarts mid-tone carries none of
  // its own history across.
  @Test
  fun aMixedToneResampledFromAnotherRateRunsUnbroken() =
    runTest {
      val samples = mixToneOf(sampleRate = 44_100)

      assertEquals(expectedFrames(), samples.length)
      assertUnbroken(samples)
    }

  // Every window boundary is a place the mix can break, so the fixture is driven at a window far
  // shorter than the one an export uses and crosses seven of them.
  // What the windows add up to has to be what one pass would have produced. A resampler restarting
  // at a boundary is allowed to differ slightly, so this compares against a tolerance rather than
  // asserting the two are identical.
  @Test
  fun windowedAndOneShotAgree() =
    runTest {
      val windowed = mixToneOf(sampleRate = 44_100)
      val oneShot = mixToneOf(sampleRate = 44_100, window = MIX_DURATION)

      assertEquals(oneShot.length, windowed.length)
      var worst = 0f
      for (index in 0 until oneShot.length) {
        worst = maxOf(worst, abs(oneShot.at(index) - windowed.at(index)))
      }
      assertTrue(worst < MAX_DIVERGENCE, "windowed and one-shot mixes diverged by $worst")
    }

  // The mixer schedules a fade as automation inside each window it renders, so a window boundary is
  // where the fade is handed over and where it breaks: a window that pins the curve at the curve's
  // own start rather than at where the fade had reached restarts the fade in every window it opens.
  // Dividing the faded mix by a flat one of the same fixture leaves the gain the node applied, with
  // the tone, the codec and the resampler all divided back out.
  @Test
  fun aFadeCrossingWindowBoundariesFollowsItsCurve() =
    runTest {
      val bytes = makeClipWithAudio(frames = CLIP_FRAMES, sampleRate = SOURCE_RATE)
      val fade = ResolvedGain(listOf(GainSegment(Duration.ZERO, CLIP_DURATION, FADE_FROM, FADE_TO)))
      val flat = mixToneOf(sampleRate = SOURCE_RATE, bytes = bytes)
      val faded = mixToneOf(sampleRate = SOURCE_RATE, bytes = bytes, gain = fade)

      assertEquals(flat.length, faded.length)
      assertFollows(faded, flat, fade)
    }

  private suspend fun mixToneOf(
    sampleRate: Int,
    window: Duration = WINDOW,
    gain: ResolvedGain = FLAT,
    bytes: ByteArray? = null,
  ): Float32Array {
    val source = MediaSource.Bytes(bytes ?: makeClipWithAudio(frames = CLIP_FRAMES, sampleRate = sampleRate))
    val sources = SourceCache()
    try {
      val windows = mutableListOf<AudioBuffer>()
      BrowserAudioMix.mixInto(
        tracks = listOf(trackOf(source, sampleRate, gain)),
        format = AudioFormat(sampleRate = OUTPUT_RATE, channelCount = 1),
        duration = MIX_DURATION,
        sources = sources,
        window = window,
      ) { windows += it }
      return joined(windows)
    } finally {
      sources.close()
    }
  }

  /**
   * Every window laid end to end, which is what the encoder is handed one call at a time and so is
   * what the assertions have to run against.
   */
  private fun joined(windows: List<AudioBuffer>): Float32Array {
    val total = windows.sumOf { it.length }
    val joined = OfflineAudioContext(1, total, OUTPUT_RATE.toFloat()).createBuffer(1, total, OUTPUT_RATE.toFloat())
    var at = 0
    windows.forEach { window ->
      val samples = Float32Array(window.length)
      window.copyFromChannel(samples, 0, 0)
      joined.copyToChannel(samples, 0, at)
      at += window.length
    }
    return joined.getChannelData(0)
  }

  private fun trackOf(
    source: MediaSource,
    sampleRate: Int,
    gain: ResolvedGain,
  ): ResolvedTrack =
    ResolvedTrack(
      content = TrackContent.AudioAndVideo,
      looping = false,
      start = Duration.ZERO,
      clips =
        listOf(
          ResolvedClip(
            source = source,
            info =
              MediaInfo(
                duration = CLIP_DURATION,
                video = null,
                audio = AudioTrackInfo(trackCodecOf("opus"), sampleRate, 1, null),
                isExportable = true,
              ),
            start = Duration.ZERO,
            end = CLIP_DURATION,
            effects = emptyList(),
            gain = gain,
            startsAtKeyFrame = true,
            span = TimeRange.of(Duration.ZERO, CLIP_DURATION),
          ),
        ),
    )

  /**
   * Asserts the tone neither jumps nor drops out anywhere in the analysed span.
   *
   * A 440Hz tone at the output rate moves at most `2 * PI * 440 / rate` of its own amplitude
   * between one frame and the next, so anything past [MAX_STEP] is a discontinuity rather than the
   * signal. Block energy catches the other half: a gap reads as full amplitude either side of a
   * block with almost none in it, which no step measured across a fade would notice.
   */
  private fun assertUnbroken(samples: Float32Array) {
    val from = (ANALYSIS_MARGIN.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()
    val to = samples.length - from

    var maxStep = 0f
    for (index in from + 1 until to) {
      maxStep = maxOf(maxStep, abs(samples.at(index) - samples.at(index - 1)))
    }
    assertTrue(maxStep < MAX_STEP, "the mix stepped by $maxStep between two frames, expected a continuous tone")

    val blocks = blockEnergy(samples, from, to)
    val median = blocks.sorted()[blocks.size / 2]
    val quietest = blocks.min()
    assertTrue(median > SILENCE, "the analysed span was silent, median block energy was $median")
    assertTrue(
      quietest > median * MIN_BLOCK_RATIO,
      "one block of the mix fell to $quietest against a median of $median, expected an unbroken tone",
    )
  }

  /**
   * Asserts the gain the mix applied follows [fade] everywhere in the analysed span.
   *
   * [faded] and [flat] are the same fixture through the same windows, so the ratio of one block's
   * energy to the other's is what the gain node contributed and nothing else. Every block is
   * measured rather than only the ones on a boundary, which puts the assertion through the middle
   * of each window as well as across its seams. The clip sits at the head of the timeline, so mix
   * time and clip time are the same time here.
   */
  private fun assertFollows(
    faded: Float32Array,
    flat: Float32Array,
    fade: ResolvedGain,
  ) {
    val from = (ANALYSIS_MARGIN.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()
    val to = faded.length - from
    val size = (GAIN_BLOCK.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()

    var measured = 0
    var worst = 0f
    var worstAt = Duration.ZERO
    for (start in from until to - size step size) {
      val reference = rms(flat, start, size)
      if (reference <= SILENCE) continue
      val at = ((start + size / 2).toDouble() / OUTPUT_RATE).toDuration(DurationUnit.SECONDS)
      val drift = abs(rms(faded, start, size) / reference - fade.gainAt(at))
      measured++
      if (drift > worst) {
        worst = drift
        worstAt = at
      }
    }

    assertTrue(measured > MIN_MEASURED_BLOCKS, "only $measured blocks carried enough tone to measure the fade")
    assertTrue(worst < MAX_GAIN_DRIFT, "the mix was off its fade by $worst at $worstAt")
  }

  private fun blockEnergy(
    samples: Float32Array,
    from: Int,
    to: Int,
  ): List<Float> {
    val size = (BLOCK.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()
    return (from until to - size step size).map { start -> rms(samples, start, size) }
  }

  private fun rms(
    samples: Float32Array,
    from: Int,
    size: Int,
  ): Float {
    var sum = 0.0
    for (index in from until from + size) {
      val value = samples.at(index).toDouble()
      sum += value * value
    }
    return sqrt(sum / size).toFloat()
  }

  private fun expectedFrames(): Int = (MIX_DURATION.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()

  private companion object {
    const val OUTPUT_RATE = 48_000

    // A rate the output does not run at, so the fade is measured through the resampler rather than
    // around it.
    const val SOURCE_RATE = 44_100
    const val CLIP_FRAMES = 120
    val CLIP_DURATION = 4.seconds
    val MIX_DURATION = 3500.milliseconds
    val FLAT = ResolvedGain.constant(1f, Duration.ZERO, CLIP_DURATION)

    // Opus pads both ends of what it encodes, so the analysed span starts and stops inside the
    // tone rather than at the edges of the buffer.
    val ANALYSIS_MARGIN = 200.milliseconds
    val BLOCK = 5.milliseconds
    val WINDOW = 500.milliseconds

    // Four times what a 440Hz tone at this rate can move in one frame, which leaves room for what
    // a lossy round trip adds without leaving room for a click.
    const val MAX_STEP = 0.05f
    const val MIN_BLOCK_RATIO = 0.3f
    const val SILENCE = 0.01f

    // Well under the tone's own amplitude, and far under what a dropped or repeated frame costs.
    const val MAX_DIVERGENCE = 0.02f

    // A fade that stays well clear of silence, so a block's energy is a reading of the gain rather
    // than of what is left of the tone.
    const val FADE_FROM = 1f
    const val FADE_TO = 0.25f

    // Long enough that a block's energy settles over several cycles of the tone, short enough that
    // the fade barely moves inside one.
    val GAIN_BLOCK = 20.milliseconds

    // The analysed span runs 3.1s at a block every 20ms, so a run measuring far fewer than this
    // went quiet somewhere rather than faded.
    const val MIN_MEASURED_BLOCKS = 100

    // A gain node applies its ramp sample by sample, so what is left here is what a block's energy
    // reading costs rather than anything the automation did.
    const val MAX_GAIN_DRIFT = 0.02f
  }
}
