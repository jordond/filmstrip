@file:OptIn(ExperimentalWasmJsInterop::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.BrowserMixSeamTest.Companion.MAX_STEP
import dev.jordond.filmstrip.webcodecs.internal.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.DecodeCache
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

  // A looping track lays the same clip down over and over, and every pass a window holds whole asks
  // the source for the same stretch, so the run of them is one decode. A key measured from each
  // pass's own offset drifts by an ulp between passes and decodes every one of them, which nothing
  // in the rendered output would show.
  @Test
  fun everyWholePassOfALoopingClipDecodesOnce() =
    runTest {
      val source = MediaSource.Bytes(makeClipWithAudio(frames = CLIP_FRAMES, sampleRate = SOURCE_RATE))
      val sources = SourceCache()
      val decoded = DecodeCache()
      val run = LOOP_PASS * LOOP_PASSES + LOOP_SLACK
      val windows = mutableListOf<AudioBuffer>()
      try {
        BrowserAudioMix.mixInto(
          tracks = listOf(loopedTrackOf(source)),
          format = AudioFormat(sampleRate = OUTPUT_RATE, channelCount = 1),
          duration = run,
          sources = sources,
          window = run,
          decoded = decoded,
        ) { windows += it }
      } finally {
        sources.close()
      }

      // Without a tone in the output the decode could have come back empty and been cached as
      // nothing, which would count one decode and prove nothing.
      val samples = joined(windows)
      val middle = samples.length / 2
      val size = (GAIN_BLOCK.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()
      assertTrue(rms(samples, middle, size) > SILENCE, "the looped mix was silent, so nothing was decoded")
      assertEquals(1, decoded.decodes, "$LOOP_PASSES passes of one clip cost ${decoded.decodes} decodes")
    }

  /**
   * One trimmed clip on a looping track that opens late, under a track fade written over the whole
   * run. Every pass has to sound at the level the plan folded for where that pass falls in the
   * fade, and the run has to stop where the composition does rather than at the end of a pass.
   *
   * The two readings inside the ramp sit a quarter and three quarters along it, since a reading at
   * its midpoint agrees with a curve that is off by a factor as well as with the right one.
   */
  @Test
  fun aLoopingBedFollowsItsTrackFadeThroughEveryPass() =
    runTest {
      val bed = MediaSource.Bytes(makeClipWithAudio(frames = BED_FRAMES, sampleRate = SOURCE_RATE))
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(listOf(Clip(silentPrimary(), trim = TimeRange.of(Duration.ZERO, RUN)))),
              Track(
                clips = listOf(Clip(bed, trim = TimeRange.of(FADED_FROM, FADED_TO))),
                content = TrackContent.Audio,
                start = FADED_OPENS,
                looping = true,
                fadeIn = TRACK_FADE,
              ),
            ),
          audio = AudioSpec.Keep,
        )
      val mix = mixOf(composition)

      assertEquals(RUN, mix.duration)
      assertEquals(FADED_OFFSETS, mix.laid.map { (it.span.start - FADED_OPENS).inWholeMilliseconds })
      assertEquals(FADED_CUT, mix.laid.last().duration)
      assertEquals(framesOf(RUN), mix.faded.length)

      // A planner that restarted the ramp in every pass would fold each of them the same way and
      // the mix would agree with it, so what says the fade is one ramp over the whole run is that
      // each pass opens on the gain the pass before it closed on.
      mix.laid.zipWithNext { earlier, later ->
        val closes = earlier.gain.gainAt(earlier.duration)
        val opens = later.gain.gainAt(Duration.ZERO)
        assertTrue(
          abs(opens - closes) < ResolvedGain.PRODUCT_TOLERANCE,
          "a pass closed on $closes and the next opened on $opens, so the fade restarted",
        )
      }

      val beforeTheBed = mix.laid[0].span.start - LEAD_IN
      assertTrue(rmsAt(mix.faded, beforeTheBed) <= SILENCE, "the bed sounded at $beforeTheBed, before its track opens")
      assertLevelAt(mix, pass = 0, into = 750.milliseconds)
      assertLevelAt(mix, pass = 1, into = 550.milliseconds)
      assertLevelAt(mix, pass = 2, into = 400.milliseconds)
      assertLevelAt(mix, pass = 3, into = 1_100.milliseconds)
    }

  /**
   * Two clips on one looping track, told apart by the level the plan folded for each of them. Every
   * pass lays the loud clip then the quiet one, and the run cuts the loud one short in the last
   * pass.
   *
   * The quiet clip is read in two different passes, which is what separates a track laid pass by
   * pass from one that played its first pass and stopped.
   */
  @Test
  fun eachClipOfALoopingBedKeepsItsOwnLevelInEveryPass() =
    runTest {
      val bed = MediaSource.Bytes(makeClipWithAudio(frames = BED_FRAMES, sampleRate = SOURCE_RATE))
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(listOf(Clip(silentPrimary(), trim = TimeRange.of(Duration.ZERO, RUN)))),
              Track(
                clips =
                  listOf(
                    Clip(bed, trim = TimeRange.of(Duration.ZERO, LOUD_TO)),
                    Clip(bed, trim = TimeRange.of(QUIET_FROM, QUIET_TO), audio = AudioLevel.Volume(QUIET_LEVEL)),
                  ),
                content = TrackContent.Audio,
                start = PAIR_OPENS,
                looping = true,
              ),
            ),
          audio = AudioSpec.Keep,
        )
      val mix = mixOf(composition)

      assertEquals(RUN, mix.duration)
      assertEquals(PAIR_OFFSETS, mix.laid.map { (it.span.start - PAIR_OPENS).inWholeMilliseconds })
      assertEquals(PAIR_CUT, mix.laid.last().duration)
      assertEquals(framesOf(RUN), mix.faded.length)

      // Two ways this fails: the bed going silent after its first pass, which the tone reading
      // catches, and the quiet clip playing over the loud one rather than after it, which reads as
      // the two summed rather than as the quieter alone and so misses the level the plan folded.
      val loud = assertLevelAt(mix, pass = 2, into = 500.milliseconds)
      assertTrue(rmsAt(mix.faded, loud) > SILENCE, "the bed went silent at $loud, after its first pass")
      assertLevelAt(mix, pass = 3, into = 600.milliseconds)
      assertLevelAt(mix, pass = 5, into = 500.milliseconds)
      assertLevelAt(mix, pass = 6, into = 500.milliseconds)
    }

  /**
   * The bed track as the planner laid it, and that plan mixed twice: once at the gains it folded,
   * and once with every one of them at unity.
   */
  private class LaidMix(
    val laid: List<ResolvedClip>,
    val duration: Duration,
    val faded: Float32Array,
    val flat: Float32Array,
  )

  /**
   * Plans [composition] the way an export does and mixes what it laid, so every expectation below
   * is the planner's own rather than a number written out again here.
   */
  private suspend fun mixOf(composition: EditComposition): LaidMix {
    val lowering =
      BrowserPlanner(listOf(BuiltInEffectResolver())).lower(
        composition = composition,
        spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac),
        device = MIX_DEVICE,
        infos = probed(composition),
      )
    val render = assertNotNull(lowering.render, "the looping composition lowered to no render")
    val tracks = render.audioTracks
    return LaidMix(
      laid = tracks[BED_TRACK].clips,
      duration = render.duration,
      faded = mixed(tracks, render.duration),
      flat = mixed(tracks.atUnity(), render.duration),
    )
  }

  /**
   * Asserts the gain the mix applied [into] pass [pass] is the one the plan folded there, and hands
   * back the instant it read so a caller measuring the same place again spells the pass once.
   *
   * The instant comes off the slot the planner laid rather than being worked out here. The reading
   * is the ratio of this mix's block energy to the same block of the same schedule at unity, which
   * divides the tone, the codec and the resampler back out and leaves the gain. The fixtures carry
   * one tone between them, so a level is what tells two clips apart here rather than a frequency.
   */
  private fun assertLevelAt(
    mix: LaidMix,
    pass: Int,
    into: Duration,
  ): Duration {
    val at = mix.laid[pass].span.start + into
    val reference = rmsAt(mix.flat, at)
    assertTrue(reference > SILENCE, "the same schedule at unity carried no tone at $at, so nothing there reads a gain")

    val measured = rmsAt(mix.faded, at) / reference
    val expected = mix.laid[pass].gain.gainAt(into)
    assertTrue(
      abs(measured - expected) < MAX_GAIN_DRIFT,
      "the mix read $measured at $at, and pass $pass of the plan folds to $expected $into in",
    )
    return at
  }

  /**
   * A clip long enough to hold the composition open for the whole run, carrying no audio track of
   * its own. The fixtures here have one tone between them, so a primary that sounded would be
   * measured along with the bed rather than under it.
   */
  private suspend fun silentPrimary(): MediaSource =
    MediaSource.Bytes(makeClip(frames = PRIMARY_FRAMES, frameRate = PRIMARY_RATE))

  private suspend fun probed(composition: EditComposition): Map<MediaSource, MediaInfo> {
    val prober = BrowserProber()
    return composition.tracks
      .flatMap { it.clips }
      .map { it.source }
      .distinct()
      .associateWith { source -> assertIs<ProbeResult.Success>(prober.probe(source)).info }
  }

  /**
   * The same schedule with every laid gain at unity, which is what a reading is divided by.
   */
  private fun List<ResolvedTrack>.atUnity(): List<ResolvedTrack> =
    map { track ->
      ResolvedTrack(
        content = track.content,
        looping = track.looping,
        start = track.start,
        clips =
          track.clips.map { clip ->
            ResolvedClip(
              source = clip.source,
              info = clip.info,
              start = clip.start,
              end = clip.end,
              effects = clip.effects,
              gain = ResolvedGain.constant(1f, Duration.ZERO, clip.duration),
              startsAtKeyFrame = clip.startsAtKeyFrame,
              span = clip.span,
              sourceIndex = clip.sourceIndex,
            )
          },
      )
    }

  private suspend fun mixed(
    tracks: List<ResolvedTrack>,
    duration: Duration,
  ): Float32Array {
    val sources = SourceCache()
    try {
      val windows = mutableListOf<AudioBuffer>()
      BrowserAudioMix.mixInto(
        tracks = tracks,
        format = AudioFormat(sampleRate = OUTPUT_RATE, channelCount = 1),
        duration = duration,
        sources = sources,
        window = WINDOW,
      ) { windows += it }
      return joined(windows)
    } finally {
      sources.close()
    }
  }

  /**
   * One short window of [source] laid down [LOOP_PASSES] times, the way the planner lays a looping
   * track, with each pass carrying the slot it was laid at.
   */
  private fun loopedTrackOf(source: MediaSource): ResolvedTrack {
    var at = Duration.ZERO
    return ResolvedTrack(
      content = TrackContent.Audio,
      looping = true,
      start = Duration.ZERO,
      clips =
        List(LOOP_PASSES) {
          ResolvedClip(
            source = source,
            info =
              MediaInfo(
                duration = CLIP_DURATION,
                video = null,
                audio = AudioTrackInfo(trackCodecOf("opus"), SOURCE_RATE, 1, null),
                isExportable = true,
              ),
            start = Duration.ZERO,
            end = LOOP_PASS,
            effects = emptyList(),
            gain = ResolvedGain.constant(1f, Duration.ZERO, LOOP_PASS),
            startsAtKeyFrame = true,
            span = TimeRange.of(at, at + LOOP_PASS).also { at += LOOP_PASS },
            sourceIndex = 0,
          )
        },
    )
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
            sourceIndex = 0,
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

  /**
   * The energy of one block centred on [at], which is what a reading at an instant measures.
   */
  private fun rmsAt(
    samples: Float32Array,
    at: Duration,
  ): Float {
    val size = framesOf(GAIN_BLOCK)
    return rms(samples, framesOf(at) - size / 2, size)
  }

  private fun framesOf(duration: Duration): Int = (duration.toDouble(DurationUnit.SECONDS) * OUTPUT_RATE).roundToInt()

  private fun expectedFrames(): Int = framesOf(MIX_DURATION)

  private companion object {
    const val OUTPUT_RATE = 48_000

    // A rate the output does not run at, so the fade is measured through the resampler rather than
    // around it.
    const val SOURCE_RATE = 44_100
    const val CLIP_FRAMES = 120
    val CLIP_DURATION = 4.seconds

    // Short enough that several passes of it fit inside one window whole, which is where a repeated
    // pass is meant to cost nothing, and not a round binary fraction: a key measured from each
    // pass's own offset lands on the same double for a half second whatever the offset, so a half
    // second would collapse to one slice however the key was built.
    val LOOP_PASS = 1_700.milliseconds
    const val LOOP_PASSES = 4

    // Room past the last pass, so no pass sits an ulp outside the window and takes the cut path.
    val LOOP_SLACK = 200.milliseconds
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

    // The looping cases every backend runs, at the lengths they all share so a divergence shows.
    // No two of a track's opening, its pass and the composition divide evenly, and the last pass is
    // always cut, so a backend that laid whole passes or rounded one off lands somewhere else.
    val RUN = 7_300.milliseconds
    const val BED_TRACK = 1

    // How far ahead of the first pass the mix is read for a bed that should not be there yet, clear
    // of both the composition's own start and the pass's.
    val LEAD_IN = 400.milliseconds

    // Three seconds of bed at thirty frames a second, and a primary long enough to hold the
    // composition open for the whole run.
    const val BED_FRAMES = 90
    const val PRIMARY_FRAMES = 80
    const val PRIMARY_RATE = 10

    // Case 1: one clip, opening late, under a fade written over the whole run.
    val FADED_OPENS = 700.milliseconds
    val FADED_FROM = 200.milliseconds
    val FADED_TO = 1_900.milliseconds
    val TRACK_FADE = 3.seconds
    val FADED_OFFSETS = listOf(0L, 1_700L, 3_400L, 5_100L)
    val FADED_CUT = 1_500.milliseconds

    // Case 2: two clips of one source, told apart by the level the second carries.
    val PAIR_OPENS = 500.milliseconds
    val LOUD_TO = 1_100.milliseconds
    val QUIET_FROM = 1_400.milliseconds
    val QUIET_TO = 2_300.milliseconds
    const val QUIET_LEVEL = 0.4f
    val PAIR_OFFSETS = listOf(0L, 1_100L, 2_000L, 3_100L, 4_000L, 5_100L, 6_000L)
    val PAIR_CUT = 800.milliseconds

    // Enough for the planner to settle on a transcode with a mix, which is the path an export
    // carrying a bed takes. Nothing here is measured, so the ladder's own picks never show.
    val MIX_DEVICE =
      DeviceCapabilities(
        video = listOf(VideoEncoderCapability(VideoCodec.H264, null, Size(3840, 2160), null, null, false, 2)),
        audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(OUTPUT_RATE), 2)),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      )
  }
}
