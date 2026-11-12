package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.Composition
import com.google.common.collect.ImmutableList
import dev.jordond.filmstrip.InternalFilmstripApi
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
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a standing preview plays once a parameter swap changes nothing but a clip's level or its fades, read off the
 * processors its audio item runs.
 */
@OptIn(InternalFilmstripApi::class)
class Media3PreviewGainSwapTest {
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
  fun `a changed volume reaches the standing graph without a rebuild`() {
    val preview = composition(clip(UNITY)).toMedia3Preview(NO_READS)
    val quiet = ResolvedGain.constant(0.4f, Duration.ZERO, CLIP_LENGTH)

    preview.updateParameters(composition(clip(quiet)), NO_READS) shouldBe true

    preview.composition
      .stream()
      .play(CLIP_LENGTH)
      .shouldPlay(quiet)
  }

  @Test
  fun `a changed fade reaches the standing graph and ramps through its middle`() {
    val preview = composition(clip(UNITY)).toMedia3Preview(NO_READS)

    preview.updateParameters(composition(clip(FADES)), NO_READS) shouldBe true

    preview.composition
      .stream()
      .play(CLIP_LENGTH)
      .shouldPlay(FADES)
    withClue("the fades are read part way up and part way down, where they differ from both ends") {
      listOf(50.milliseconds, 100.milliseconds, 900.milliseconds).forEach { at ->
        FADES.gainAt(at) shouldNotBe 0f
        FADES.gainAt(at) shouldNotBe 1f
      }
    }
  }

  @Test
  fun `a level swapped while the stream plays is heard from the next block at the place the stream has reached`() {
    val preview = composition(clip(FADES)).toMedia3Preview(NO_READS)
    val stream = preview.composition.stream()
    val before = stream.play(SWAP_AT)
    val halved = FADES * ResolvedGain.constant(0.5f, Duration.ZERO, CLIP_LENGTH)

    preview.updateParameters(composition(clip(halved)), NO_READS) shouldBe true
    val after = stream.play(CLIP_LENGTH - SWAP_AT)

    before.shouldPlay(FADES)
    after.shouldPlay(halved, from = SWAP_AT)
  }

  @Test
  fun `a level taken back to unity plays the source as it is`() {
    val preview = composition(clip(FADES)).toMedia3Preview(NO_READS)

    preview.updateParameters(composition(clip(UNITY)), NO_READS) shouldBe true

    preview.composition
      .stream()
      .play(CLIP_LENGTH)
      .shouldPlay(UNITY)
  }

  @Test
  fun `a seek lands a swapped fade on the place it seeks to`() {
    val preview = composition(clip(UNITY)).toMedia3Preview(NO_READS)
    preview.updateParameters(composition(clip(FADES)), NO_READS) shouldBe true

    val heard = preview.composition.stream(from = SWAP_AT).play(CLIP_LENGTH - SWAP_AT)

    heard.shouldPlay(FADES, from = SWAP_AT)
  }

  @Test
  fun `a refused swap leaves the standing level in place`() {
    val preview = composition(clip(UNITY)).toMedia3Preview(NO_READS)
    val moved = composition(clip(FADES), size = Size(320, 180))

    preview.updateParameters(moved, NO_READS) shouldBe false

    preview.composition
      .stream()
      .play(CLIP_LENGTH)
      .shouldPlay(UNITY)
  }

  @Test
  fun `an export of a clip at unity alone on its track still lowers no processor for its level`() {
    composition(clip(UNITY))
      .toMedia3(NO_READS)
      .audioProcessors()
      .map { it::class } shouldContainExactly listOf(AudioStartDrop::class, AudioLengthPin::class)
  }

  // Every frame against the shared curve. media3 truncates a scaled 16-bit sample, so a frame may sit one step under.
  private fun ShortArray.shouldPlay(
    gain: ResolvedGain,
    from: Duration = Duration.ZERO,
  ) {
    forEachIndexed { frame, sample ->
      val at = from + (frame * MICROS_PER_SECOND / SAMPLE_RATE).microseconds
      val expected = (LEVEL * gain.gainAt(at)).toInt()
      withClue("frame $frame at $at") { sample.toInt() shouldBeInRange (expected - 1)..(expected + 1) }
    }
  }

  private fun Composition.audioProcessors(): List<AudioProcessor> =
    sequences
      .single()
      .editedMediaItems
      .single()
      .effects.audioProcessors

  private fun Composition.stream(from: Duration = Duration.ZERO): Stream = Stream(audioProcessors(), from)

  /**
   * One item's processors run the way media3 runs them, fed a stream holding [LEVEL] on every sample.
   */
  private class Stream(
    processors: List<AudioProcessor>,
    from: Duration,
  ) {
    private val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))

    init {
      pipeline.configure(FORMAT)
      pipeline.flush(
        AudioProcessor.StreamMetadata
          .Builder()
          .setPositionOffsetUs(from.inWholeMicroseconds)
          .build(),
      )
    }

    // The first channel of the next [length] of the stream. A pipeline with no active processor passes its input on.
    fun play(length: Duration): ShortArray {
      val frames = (length.inWholeMicroseconds * SAMPLE_RATE / MICROS_PER_SECOND).toInt()
      if (!pipeline.isOperational) return ShortArray(frames) { LEVEL }

      val input = ByteBuffer.allocateDirect(frames * CHANNELS * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
      repeat(frames * CHANNELS) { input.putShort(LEVEL) }
      input.flip()
      val heard = ShortArray(frames)
      var written = 0
      while (written < frames) {
        pipeline.queueInput(input)
        val output = pipeline.output
        while (output.hasRemaining()) {
          heard[written++] = output.getShort()
          repeat(CHANNELS - 1) { output.getShort() }
        }
      }
      return heard
    }
  }

  private fun composition(
    clip: ResolvedClip,
    size: Size = Size(640, 360),
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(content = TrackContent.Audio, looping = false, start = Duration.ZERO, clips = listOf(clip)),
        ),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = size,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = size,
      fill = Fill.Black,
      duration = CLIP_LENGTH,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
      mixAudioEffects = emptyList(),
    )

  private fun clip(gain: ResolvedGain): ResolvedClip =
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
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNELS,
                bitrate = null,
                language = null,
                isDefault = true,
              ),
            ),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = CLIP_LENGTH,
      effects = emptyList(),
      gain = gain,
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, CLIP_LENGTH),
      sourceIndex = 0,
      audioEffects = emptyList(),
    )

  private companion object {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val LEVEL: Short = 10_000
    val MICROS_PER_SECOND = 1.seconds.inWholeMicroseconds
    val CLIP_LENGTH = 1.seconds
    val SWAP_AT = 100.milliseconds
    val NO_READS = Media3ClipReads(emptyMap(), emptyMap(), emptyMap())
    val FORMAT = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)
    val UNITY = ResolvedGain.constant(1f, Duration.ZERO, CLIP_LENGTH)

    // Up over the first 200 ms and down over the last 200 ms.
    val FADES =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 200.milliseconds, 0f, 1f),
          GainSegment(200.milliseconds, 800.milliseconds, 1f, 1f),
          GainSegment(800.milliseconds, CLIP_LENGTH, 1f, 0f),
        ),
      )
  }
}
