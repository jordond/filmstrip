package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.durationAfterEffectsUs
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.AudioPreroll
import dev.jordond.filmstrip.media3.internal.bounds.AudioWindow
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a clip's audio reaches media3 on an export, where filmstrip opens it on the trim start and runs its speed, and on
 * a preview and a copy, where it does neither.
 */
class Media3AudioSpeedTest {
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
  fun `an exported clip opens on the unit before the one holding its trim start and runs no media3 speed`() {
    val clip = clip(SLOW)

    val item = composition(clip).toMedia3(reads(clip)).audioItem()

    item.mediaItem.clippingConfiguration.startPositionUs shouldBe UNIT.inWholeMicroseconds
    item.mediaItem.clippingConfiguration.endPositionUs shouldBe (clip.end + ENCODER_DELAY).inWholeMicroseconds
    item.speedProvider shouldBeSameInstanceAs SpeedProvider.DEFAULT
  }

  // media3 keeps the encoder delay of an item clipped past the source's start, so its first frame sits the delay ahead
  // of its unit and the drop runs the delay longer.
  @Test
  fun `an exported clip opened past the source's start drops the encoder delay with its lead`() {
    val clip = clip(SLOW)

    val drop = composition(clip).toMedia3(reads(clip)).audioItem().startDrop()

    drop.getDurationAfterProcessorApplied(DECODED.inWholeMicroseconds) shouldBe
      (DECODED - (TRIM_START - UNIT + ENCODER_DELAY)).inWholeMicroseconds
  }

  @Test
  fun `an exported clip opened on the source's start drops only its lead`() {
    val clip = clip(SLOW, start = 10.milliseconds)

    val drop = composition(clip).toMedia3(reads(clip, Duration.ZERO)).audioItem().startDrop()

    drop.getDurationAfterProcessorApplied(DECODED.inWholeMicroseconds) shouldBe
      (DECODED - 10.milliseconds).inWholeMicroseconds
  }

  @Test
  fun `an exported clip drops to its trim start then stretches and pins to its span`() {
    listOf(1.0, SLOW, 0.3, 1.7).forEach { speed ->
      val clip = clip(speed)

      val item = composition(clip).toMedia3(reads(clip)).audioItem()

      val stretches = if (speed == 1.0) emptyList() else listOf(SonicAudioProcessor::class)
      item.effects.audioProcessors.map { it::class } shouldContainExactly
        listOf(AudioStartDrop::class) + stretches + AudioLengthPin::class
      item.durationAfterEffectsUs((clip.end + ENCODER_DELAY - UNIT).inWholeMicroseconds) shouldBe
        outputLengthOf(clip.sourceDuration, speed).inWholeMicroseconds
    }
  }

  @Test
  fun `an exported clip opened on the source's start is clipped the encoder delay past its end`() {
    val clip = clip(SLOW, start = Duration.ZERO)

    val item = composition(clip).toMedia3(reads(clip, Duration.ZERO)).audioItem()

    item.mediaItem.clippingConfiguration.startPositionUs shouldBe 0
    item.mediaItem.clippingConfiguration.endPositionUs shouldBe (clip.end + ENCODER_DELAY).inWholeMicroseconds
    item.durationAfterEffectsUs((clip.end + ENCODER_DELAY).inWholeMicroseconds) shouldBe
      clip.outputDuration.inWholeMicroseconds
  }

  @Test
  fun `an exported clip whose unit was never read opens on its trim start`() {
    val clip = clip(SLOW)

    val item = composition(clip).toMedia3(Media3ClipReads.None).audioItem()

    item.mediaItem.clippingConfiguration.startPositionUs shouldBe clip.start.inWholeMicroseconds
    item.durationAfterEffectsUs(clip.sourceDuration.inWholeMicroseconds) shouldBe
      clip.outputDuration.inWholeMicroseconds
  }

  @Test
  fun `a preview keeps media3's speed and opens on the trim start`() {
    val clip = clip(SLOW)

    val item = composition(clip).toMedia3Preview(reads(clip)).composition.audioItem()

    item.speedProvider shouldNotBe SpeedProvider.DEFAULT
    item.mediaItem.clippingConfiguration.startPositionUs shouldBe clip.start.inWholeMicroseconds
    item.effects.audioProcessors.none { it is AudioStartDrop || it is SonicAudioProcessor } shouldBe true
  }

  @Test
  fun `a copy runs no processors and reads no unit`() {
    val clip = clip(1.0)
    val copy = composition(clip, ExportPath.Copy)

    copy
      .toMedia3(reads(clip))
      .sequences
      .last()
      .editedMediaItems
      .single()
      .effects.audioProcessors
      .shouldBeEmpty()
    runBlocking { copy.readClips() } shouldBeSameInstanceAs Media3ClipReads.None
  }

  // A preview and a thumbnail lower with media3's own speed and never open on a unit, so their reads leave it out.
  @Test
  fun `the reads a preview takes read no audio unit`() {
    val clip = clip(SLOW)

    runBlocking { composition(clip).readClips() } shouldBeSameInstanceAs Media3ClipReads.None
  }

  private fun EditedMediaItem.startDrop(): AudioStartDrop =
    effects.audioProcessors.first().shouldBeInstanceOf<AudioStartDrop>()

  private fun Composition.audioItem(): EditedMediaItem =
    sequences
      .last()
      .editedMediaItems
      .single()

  private fun reads(
    clip: ResolvedClip,
    unit: Duration = UNIT,
  ): Media3ClipReads =
    Media3ClipReads(
      emptyMap(),
      emptyMap(),
      mapOf(
        AudioWindow(
          clip.source,
          clip.start,
          checkNotNull(clip.audioTrack).ordinal,
        ) to AudioPreroll(unit, ENCODER_DELAY),
      ),
    )

  private fun composition(
    clip: ResolvedClip,
    path: ExportPath = ExportPath.Transcode,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(content = TrackContent.Audio, looping = false, start = Duration.ZERO, clips = listOf(clip)),
        ),
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
      duration = clip.outputDuration,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = path,
    )

  private fun clip(
    speed: Double,
    start: Duration = TRIM_START,
  ): ResolvedClip {
    val length = outputLengthOf(TRIM_END - start, speed)
    return ResolvedClip(
      source = MediaSource.of("clip.m4a"),
      info =
        MediaInfo(
          duration = SOURCE_LENGTH,
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
      start = start,
      end = TRIM_END,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, length),
      sourceIndex = 0,
      speed = speed,
    )
  }

  private companion object {
    const val SLOW = 1.0 / 32
    val SOURCE_LENGTH = 2.seconds
    val TRIM_START = 210.milliseconds
    val TRIM_END = 335.milliseconds

    // The AAC access unit of 1024 frames at 48 kHz before the one holding the trim start.
    val UNIT = 170_666.microseconds

    // One access unit of priming at 48 kHz, which is what an AAC encoder names.
    val ENCODER_DELAY = 21_334.microseconds

    // How much audio an item hands its first processor, long enough that no drop runs out of it.
    val DECODED = 1.seconds
  }
}
