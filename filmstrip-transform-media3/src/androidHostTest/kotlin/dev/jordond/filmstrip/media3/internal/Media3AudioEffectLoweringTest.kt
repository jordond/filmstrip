package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where a plan's audio effects land in the media3 graph on an export and on a preview, and what a parameter swap does
 * to the processors a standing preview runs them on.
 */
@OptIn(InternalFilmstripApi::class)
class Media3AudioEffectLoweringTest {
  @BeforeTest
  fun stubUri() {
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    every { Uri.parse(any()) } returns mockk(relaxed = true)
  }

  @AfterTest
  fun releaseUri() {
    unmockkAll()
  }

  @Test
  fun `an exported clip runs its effects on its full length, opening where it sits on the timeline`() {
    val plan = composition(clip(Duration.ZERO), clip(CLIP_LENGTH, listOf(FIRST, SECOND)))

    val items = plan.toMedia3(NO_READS).audioItems()

    items[0].map { it::class } shouldContainExactly listOf(AudioStartDrop::class, AudioLengthPin::class)
    items[1].map { it::class } shouldContainExactly
      listOf(AudioStartDrop::class, AudioLengthPin::class, KernelAudioProcessor::class)
    val kernels = items[1].filterIsInstance<KernelAudioProcessor>().single()
    kernels.start shouldBe CLIP_LENGTH
    kernels.effects shouldContainExactly listOf(FIRST, SECOND)
  }

  @Test
  fun `a preview runs a clip's effects from where it sits on the timeline too`() {
    val plan = composition(clip(Duration.ZERO), clip(CLIP_LENGTH, listOf(FIRST)))

    val items = plan.toMedia3Preview(NO_READS).composition.audioItems()

    items[1].map { it::class } shouldContainExactly listOf(KernelAudioProcessor::class, LiveGainProcessor::class)
    items[1].filterIsInstance<KernelAudioProcessor>().single().start shouldBe CLIP_LENGTH
  }

  @Test
  fun `the composition's effects run on the mix from the start of the timeline`() {
    val plan = composition(clip(Duration.ZERO), mix = listOf(FIRST))

    val mix = plan.toMedia3(NO_READS).effects.audioProcessors

    val kernels = mix.single() as KernelAudioProcessor
    kernels.start shouldBe Duration.ZERO
    kernels.effects shouldContainExactly listOf(FIRST)
  }

  // media3's mix ends with its last audio sequence, so the mix's effects are given silence that runs the whole
  // timeline, whatever the tracks lay.
  @Test
  fun `the composition's effects run over a sequence of silence as long as the timeline`() {
    val plan = composition(clip(Duration.ZERO), mix = listOf(FIRST))

    val lowered = plan.toMedia3(NO_READS)

    lowered.sequences.size shouldBe 2
    val silence = lowered.sequences.last()
    silence.trackTypes shouldBe setOf(C.TRACK_TYPE_AUDIO)
    silence.editedMediaItems.map { it.durationUs } shouldContainExactly listOf(plan.duration.inWholeMicroseconds)
    composition(clip(Duration.ZERO)).toMedia3(NO_READS).sequences.size shouldBe 1
  }

  @Test
  fun `a plan with no audio effects lowers no processor for them`() {
    val lowered = composition(clip(Duration.ZERO)).toMedia3(NO_READS)

    lowered.effects.audioProcessors.shouldBeEmpty()
    lowered
      .audioItems()
      .flatten()
      .filterIsInstance<KernelAudioProcessor>()
      .shouldBeEmpty()
  }

  @Test
  fun `a parameter swap hands the standing processors the new effects`() {
    val preview = composition(clip(Duration.ZERO, listOf(FIRST)), mix = listOf(FIRST)).toMedia3Preview(NO_READS)
    val clipKernels =
      preview.composition
        .audioItems()[0]
        .filterIsInstance<KernelAudioProcessor>()
        .single()
    val mixKernels =
      preview.composition.effects.audioProcessors
        .single() as KernelAudioProcessor

    val swapped =
      preview.updateParameters(composition(clip(Duration.ZERO, listOf(SECOND)), mix = listOf(SECOND, FIRST)), NO_READS)

    swapped shouldBe true
    preview.composition
      .audioItems()[0]
      .filterIsInstance<KernelAudioProcessor>()
      .single() shouldBeSameInstanceAs
      clipKernels
    clipKernels.effects shouldContainExactly listOf(SECOND)
    mixKernels.effects shouldContainExactly listOf(SECOND, FIRST)
  }

  @Test
  fun `a stream gaining its first effect or losing its last takes a rebuild`() {
    val plain = composition(clip(Duration.ZERO))
    val effected = composition(clip(Duration.ZERO, listOf(FIRST)))
    val mixed = composition(clip(Duration.ZERO), mix = listOf(FIRST))

    plain.toMedia3Preview(NO_READS).updateParameters(effected, NO_READS) shouldBe false
    effected.toMedia3Preview(NO_READS).updateParameters(plain, NO_READS) shouldBe false
    plain.toMedia3Preview(NO_READS).updateParameters(mixed, NO_READS) shouldBe false
  }

  // Both tracks open at the same time, so only where a processor sits says which stream a change belongs to.
  @Test
  fun `an effect moving to another track that opens at the same time takes a rebuild`() {
    val onFirst = composition(clip(Duration.ZERO, listOf(FIRST)), clip(Duration.ZERO))
    val onSecond = composition(clip(Duration.ZERO), clip(Duration.ZERO, listOf(FIRST)))

    onFirst.toMedia3Preview(NO_READS).updateParameters(onSecond, NO_READS) shouldBe false
  }

  @Test
  fun `a refused swap leaves the standing effects in place`() {
    val preview = composition(clip(Duration.ZERO, listOf(FIRST)), clip(CLIP_LENGTH)).toMedia3Preview(NO_READS)
    val kernels =
      preview.composition
        .audioItems()[0]
        .filterIsInstance<KernelAudioProcessor>()
        .single()

    val next = composition(clip(Duration.ZERO, listOf(SECOND)), clip(CLIP_LENGTH, listOf(SECOND)))
    preview.updateParameters(next, NO_READS) shouldBe false

    kernels.effects shouldContainExactly listOf(FIRST)
  }

  // Each track's one clip, in track order. A clip alone on its track is brought to no level, which media3's mixer
  // would need the platform for.
  private fun Composition.audioItems() =
    sequences.map { sequence ->
      sequence.editedMediaItems
        .last()
        .effects.audioProcessors
    }

  private fun composition(
    vararg clips: ResolvedClip,
    mix: List<ResolvedAudioEffect> = emptyList(),
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        clips.map { clip ->
          ResolvedTrack(content = TrackContent.Audio, looping = false, start = clip.span.start, clips = listOf(clip))
        },
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = Size(640, 360),
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = Size(640, 360),
      fill = Fill.Black,
      duration = clips.maxOf { it.span.start } + CLIP_LENGTH,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
      mixAudioEffects = mix,
    )

  private fun clip(
    at: Duration,
    audioEffects: List<ResolvedAudioEffect> = emptyList(),
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("clip.m4a"),
      info =
        MediaInfo(
          duration = 2.seconds,
          video = null,
          audioTracks =
            listOf(
              AudioTrackInfo(
                id = AudioTrackId(1),
                codec = trackCodecOf("mp4a"),
                sampleRate = 48_000,
                channelCount = 2,
                bitrate = null,
                language = null,
                isDefault = true,
              ),
            ),
          isExportable = true,
        ),
      start = TRIM_START,
      end = TRIM_START + CLIP_LENGTH,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, CLIP_LENGTH),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + CLIP_LENGTH),
      sourceIndex = 0,
      audioEffects = audioEffects,
    )

  private class Untouched : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) = Unit
  }

  private companion object {
    val TRIM_START = 210.milliseconds
    val CLIP_LENGTH = 500.milliseconds
    val NO_READS = Media3ClipReads(emptyMap(), emptyMap(), emptyMap())
    val FIRST = ResolvedAudioEffect("first", PlatformAudioEffect { Untouched() })
    val SECOND = ResolvedAudioEffect("second", PlatformAudioEffect { Untouched() })
  }
}
