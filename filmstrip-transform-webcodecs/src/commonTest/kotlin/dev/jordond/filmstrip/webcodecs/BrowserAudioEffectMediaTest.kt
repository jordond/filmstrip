@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.TimelineBursts
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.audio.StretchedAudio
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Audio effects in the browser's export mix and its preview, read as samples.
 *
 * [TimelineBursts] says which timeline position a kernel was handed and what ran after it. A delay that reports its
 * latency says whether the host took it out again.
 */
class BrowserAudioEffectMediaTest {
  // The second clip opens off the burst grid, so a kernel counted from the clip's own start would be heard elsewhere.
  @Test
  fun aClipEffectReadsTheTimelineAndRunsAheadOfTheClipsGain() =
    runTest {
      val source = toneSource()
      val first = clipOf(source, FIRST_CLIP)
      val second = clipOf(source, CLIP, at = FIRST_CLIP, gain = CLIP_GAIN, effects = listOf(bursts()))

      val mixed = mix(listOf(first, second), FIRST_CLIP + CLIP)

      mixed.samples.size shouldBe framesOf(FIRST_CLIP + CLIP, OUTPUT_RATE)
      mixed.toneOver(FIRST_CLIP / 2, READ, TimelineBursts.TONE_HZ) shouldBeLessThan AMPLITUDE * SILENT
      mixed.shouldHearBursts(SECOND_CLIP_BURSTS, AMPLITUDE * CLIP_GAIN)
    }

  @Test
  fun aSpedClipsEffectRunsAfterTheStretch() =
    runTest {
      val clip = clipOf(toneSource(), CLIP, speed = SPEED, effects = listOf(bursts()))

      val mixed = mix(listOf(clip), clip.outputDuration)

      mixed.samples.size shouldBe framesOf(clip.outputDuration, OUTPUT_RATE)
      mixed.shouldHearBursts(1..4, AMPLITUDE)
    }

  @Test
  fun aCompositionEffectRunsOnTheMixAfterEveryLevel() =
    runTest {
      val clip = clipOf(toneSource(), CLIP, gain = CLIP_GAIN)

      val mixed = mix(listOf(clip), CLIP, mixEffects = listOf(bursts()))

      mixed.samples.size shouldBe framesOf(CLIP, OUTPUT_RATE)
      mixed.shouldHearBursts(1..4, AMPLITUDE)
    }

  // No clip lays any audio, so the effect's own sound is all there is and it runs the length of the timeline.
  @Test
  fun aCompositionEffectRunsOverATimelineNoClipCovers() =
    runTest {
      val mixed = mix(emptyList(), CLIP, mixEffects = listOf(bursts()))

      mixed.samples.size shouldBe framesOf(CLIP, OUTPUT_RATE)
      mixed.shouldHearBursts(1..4, AMPLITUDE)
    }

  // A window boundary is where a kernel that started over would lose its place, so the mix crosses a good many.
  @Test
  fun anEffectsLatencyIsTakenOutAtBothScopesAcrossWindows() =
    runTest {
      val source = toneSource()
      val plain = mix(listOf(clipOf(source, CLIP)), CLIP)

      val onClip = mix(listOf(clipOf(source, CLIP, effects = listOf(delay()))), CLIP, window = SHORT_WINDOW)
      val onMix = mix(listOf(clipOf(source, CLIP)), CLIP, window = SHORT_WINDOW, mixEffects = listOf(delay()))

      listOf("the clip" to onClip, "the mix" to onMix).forEach { (scope, delayed) ->
        withClue("a delay on $scope") {
          delayed.samples.size shouldBe plain.samples.size
          val worst = plain.samples.indices.maxOf { abs(delayed.samples[it] - plain.samples[it]) }
          withClue("diverged from the mix without it by $worst") { worst shouldBeLessThan MAX_DIVERGENCE }
        }
      }
    }

  @Test
  fun aClipEffectCrossesWindowsOnTheTimeline() =
    runTest {
      val clip = clipOf(toneSource(), CLIP, gain = CLIP_GAIN, effects = listOf(bursts()))

      val mixed = mix(listOf(clip), CLIP, window = SHORT_WINDOW)

      mixed.shouldHearBursts(1..5, AMPLITUDE * CLIP_GAIN)
    }

  @Test
  fun aKernelThatThrowsFailsTheMix() =
    runTest {
      val broken = ResolvedAudioEffect("test.broken", PlatformAudioEffect { ThrowingKernel() })
      val source = toneSource()

      listOf(
        suspend { mix(listOf(clipOf(source, CLIP, effects = listOf(broken))), CLIP) },
        suspend { mix(listOf(clipOf(source, CLIP)), CLIP, mixEffects = listOf(broken)) },
      ).forEach { run ->
        assertFailsWith<IllegalStateException> { run() }.message shouldContain THROWN
      }
    }

  // A pass with an effect reads where it sits on the timeline, so no two of them share a decoded buffer.
  @Test
  fun everyPassOfAClipWithAnEffectIsCountedOnItsOwn() {
    val source = MediaSource.Uri("a")
    val shared = List(PASSES) { clipOf(source, CLIP, at = CLIP * it) }
    val effected = List(PASSES) { clipOf(source, CLIP, at = CLIP * it, effects = listOf(bursts())) }
    val window = CLIP * PASSES

    val output = BrowserAudioMix.peakBytes(emptyList(), FORMAT, window, window)
    val one = BrowserAudioMix.peakBytes(listOf(trackOf(shared.take(1))), FORMAT, window, window) - output

    BrowserAudioMix.peakBytes(listOf(trackOf(effected)), FORMAT, window, window) - output shouldBe one * PASSES
    BrowserAudioMix.peakBytes(listOf(trackOf(shared)), FORMAT, window, window) - output shouldBeLessThan one * PASSES
  }

  @Test
  fun aPreviewHearsAClipEffectOnTheTimelineFromWhereItStarts() =
    runTest {
      val clip = clipOf(toneSource(), CLIP, gain = CLIP_GAIN, effects = listOf(bursts()))

      val heard = previewed(PREVIEW_FROM) { listOf(trackOf(listOf(clip))) }

      heard.shouldHearBursts(1..4, AMPLITUDE * CLIP_GAIN)
    }

  @Test
  fun aPreviewTakesACompositionEffectsLatencyOut() =
    runTest {
      val clip = clipOf(toneSource(), CLIP, effects = listOf(bursts()))

      val heard = previewed(PREVIEW_FROM, mixEffects = { listOf(DELAY) }) { listOf(trackOf(listOf(clip))) }

      heard.shouldHearBursts(1..4, AMPLITUDE)
    }

  @Test
  fun aPreviewHearsAnEffectChangeWithoutStartingOver() =
    runTest {
      val source = toneSource()
      val before = listOf(trackOf(listOf(clipOf(source, CLIP, effects = listOf(bursts())))))
      val after = listOf(trackOf(listOf(clipOf(source, CLIP, effects = listOf(bursts(CHANGED_AMPLITUDE))))))
      var pumps = 0

      val heard =
        previewed(Duration.ZERO, onPump = { pumps = it }) {
          if (pumps <
            CHANGE_AFTER_PUMPS
          ) {
            before
          } else {
            after
          }
        }

      heard.shouldHearBursts(1..1, AMPLITUDE)
      heard.shouldHearBursts(4..5, CHANGED_AMPLITUDE)
    }

  private suspend fun previewed(
    from: Duration,
    mixEffects: () -> List<ResolvedAudioEffect> = { emptyList() },
    onPump: (Int) -> Unit = {},
    tracks: () -> List<ResolvedTrack>,
  ): Heard {
    val sources = SourceCache()
    val windows = mutableListOf<StretchedAudio>()
    val preview = BrowserAudioPreview(tracks, sources, mixEffects, channelCount = 1) { windows += it }
    try {
      preview.start(from)
      repeat(PREVIEW_PUMPS) {
        onPump(it + 1)
        preview.pump(from + PUMP_STEP * (it + 1))
      }
    } finally {
      preview.release()
      sources.close()
    }

    val rate = windows.first().sampleRate
    val opens = windows.first().opensAtSeconds
    opens shouldBe (from.toDouble(DurationUnit.SECONDS) plusOrMinus 1.0 / rate)
    var frames = 0
    windows.forEach { window ->
      withClue("a window opens where the one before closed") {
        window.opensAtSeconds shouldBe (opens + frames.toDouble() / rate plusOrMinus 1.0 / rate)
      }
      frames += window.frames
    }
    val lead = (opens * rate).roundToInt()
    val samples = FloatArray(lead + frames)
    var at = lead
    windows.forEach { window ->
      window.channels[0].copyInto(samples, at, 0, window.frames)
      at += window.frames
    }
    return Heard(samples, rate)
  }

  private suspend fun mix(
    clips: List<ResolvedClip>,
    duration: Duration,
    window: Duration = BrowserAudioMix.WINDOW,
    mixEffects: List<ResolvedAudioEffect> = emptyList(),
  ): Heard {
    val sources = SourceCache()
    try {
      val joined = FloatArray(framesOf(duration, OUTPUT_RATE))
      var at = 0
      BrowserAudioMix.mixInto(
        tracks = listOf(trackOf(clips)),
        format = FORMAT,
        duration = duration,
        sources = sources,
        window = window,
        mixEffects = mixEffects,
      ) { buffer ->
        val samples = buffer.getChannelData(0)
        for (index in 0 until buffer.length) joined[at + index] = samples.at(index)
        at += buffer.length
      }
      at shouldBe joined.size
      return Heard(joined, OUTPUT_RATE)
    } finally {
      sources.close()
    }
  }

  private fun Heard.shouldHearBursts(
    bursts: IntRange,
    level: Float,
  ) {
    bursts.forEach { index ->
      val opens = TimelineBursts.burstStart(index)
      withClue("burst $index, opening at $opens") {
        toneOver(opens + BURST_EDGE, READ, TimelineBursts.TONE_HZ) shouldBe (level plusOrMinus level * TOLERANCE)
      }
      withClue("the silence after burst $index") {
        toneOver(opens + TimelineBursts.LENGTH + BURST_EDGE, GAP_READ, TimelineBursts.TONE_HZ) shouldBeLessThan
          level * SILENT
      }
    }
  }

  private fun Heard.toneOver(
    from: Duration,
    length: Duration,
    frequencyHz: Int,
  ): Float =
    ToneAnalysis.amplitudeOver(
      samples = samples,
      from = framesOf(from, sampleRate),
      length = framesOf(length, sampleRate),
      frequencyHz = frequencyHz,
      sampleRate = sampleRate,
    )

  private suspend fun toneSource(): MediaSource =
    MediaSource.Bytes(makeClipWithAudio(frames = framesOf(SOURCE, FIXTURE_RATE), frameRate = FIXTURE_RATE))

  private fun clipOf(
    source: MediaSource,
    length: Duration,
    at: Duration = Duration.ZERO,
    speed: Double = 1.0,
    gain: Float = 1f,
    effects: List<ResolvedAudioEffect> = emptyList(),
  ): ResolvedClip {
    val played = outputLengthOf(length, speed)
    return ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = SOURCE,
          video = null,
          audioTracks =
            listOf(
              AudioTrackInfo(CLIP_AUDIO_TRACK, trackCodecOf("opus"), OUTPUT_RATE, 1, null, null, isDefault = true),
            ),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(gain, Duration.ZERO, played),
      startsAtKeyFrame = true,
      span = TimeRange.of(at, at + played),
      sourceIndex = 0,
      speed = speed,
      audioEffects = effects,
    )
  }

  private fun trackOf(clips: List<ResolvedClip>): ResolvedTrack =
    ResolvedTrack(content = TrackContent.AudioAndVideo, looping = false, start = Duration.ZERO, clips = clips)

  private fun bursts(amplitude: Float = AMPLITUDE): ResolvedAudioEffect =
    ResolvedAudioEffect(TimelineBursts.ID, PlatformAudioEffect(TimelineBursts(amplitude)::newKernel))

  private fun delay(): ResolvedAudioEffect = DELAY

  private fun framesOf(
    length: Duration,
    rate: Int,
  ): Int = (length.toDouble(DurationUnit.SECONDS) * rate).roundToInt()

  private class Heard(
    val samples: FloatArray,
    val sampleRate: Int,
  )

  private class DelayKernel(
    private val frames: Int,
    private val channels: Int,
  ) : AudioKernel() {
    private val held = FloatArray(frames * channels)
    private var at = 0

    override val latencyFrames: Int get() = frames

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in 0 until frames * channels) {
        val delayed = held[at]
        held[at] = buffer[index]
        buffer[index] = delayed
        at = (at + 1) % held.size
      }
    }

    override fun reset(position: Long) {
      held.fill(0f)
      at = 0
    }
  }

  private class ThrowingKernel : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ): Unit = error(THROWN)
  }

  private companion object {
    const val OUTPUT_RATE = 48_000
    const val FIXTURE_RATE = 30
    const val AMPLITUDE = 0.5f
    const val CHANGED_AMPLITUDE = 0.2f
    const val CLIP_GAIN = 0.5f
    const val SPEED = 0.7
    const val TOLERANCE = 0.05f
    const val SILENT = 0.05f
    const val MAX_DIVERGENCE = 0.02f
    const val DELAY_FRAMES = 480
    const val PASSES = 5
    const val PREVIEW_PUMPS = 8
    const val CHANGE_AFTER_PUMPS = 2
    const val THROWN = "this kernel always throws"
    val FORMAT = AudioFormat(sampleRate = OUTPUT_RATE, channelCount = 1)
    val SOURCE = 3.seconds
    val CLIP = 2_400.milliseconds
    val FIRST_CLIP = 500.milliseconds
    val SECOND_CLIP_BURSTS = 2..6
    val SHORT_WINDOW = 200.milliseconds
    val PREVIEW_FROM = 300.milliseconds
    val PUMP_STEP = 250.milliseconds
    val BURST_EDGE = 20.milliseconds
    val READ = TimelineBursts.LENGTH - BURST_EDGE * 2
    val GAP_READ = TimelineBursts.PERIOD - TimelineBursts.LENGTH - BURST_EDGE * 2
    val DELAY = ResolvedAudioEffect("test.delay", PlatformAudioEffect { DelayKernel(DELAY_FRAMES, it.channelCount) })
  }
}
