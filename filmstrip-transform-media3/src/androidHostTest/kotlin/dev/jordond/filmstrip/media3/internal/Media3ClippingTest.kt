package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.Media3FrameBounds
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a clip's trim reaches media3, on the path that copies the samples across and on the one that
 * re-encodes them.
 *
 * The window itself is the planner's, so every assertion reads the clip it lowered rather than a
 * number written down twice. What is media3's own is where those bounds land and whether the item
 * asks the extractor to open on a sync sample.
 */
class Media3ClippingTest {
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

  // A copy has no decoder to walk to the cut, so the item has to say the start is a sync sample or
  // Transformer decodes the leading group of pictures to reach it. It writes the source's own samples,
  // so its window is the exact one in microseconds rather than one moved onto the output grid.
  @Test
  fun `a snapped trim carries its window and opens on a key frame`() {
    val clip = clip(startsAtKeyFrame = true)

    val clipping =
      composition(clip, ExportPath.Copy)
        .firstItem()
        .mediaItem.clippingConfiguration
        .shouldNotBeNull()

    clipping.startPositionUs shouldBe clip.start.inWholeMicroseconds
    clipping.endPositionUs shouldBe clip.end.inWholeMicroseconds
    clipping.startsAtKeyFrame shouldBe true
  }

  // Without the snap, the video opens on the cut and runs for the slots its span covers, and the
  // audio carries the exact window. An export that re-encodes decodes to the cut anyway, so the flag
  // going along for the ride would move a trim the caller asked to land exactly.
  @Test
  fun `an unsnapped trim opens its video on the cut and carries the exact window on its audio`() {
    val clip = clip(startsAtKeyFrame = false)
    val plan = composition(clip, ExportPath.Transcode)

    val (video, audio) =
      plan.toMedia3(Media3FrameBounds.None).sequences.map {
        (
          it.editedMediaItems
            .single()
            .mediaItem.clippingConfiguration
        ).shouldNotBeNull()
      }

    video.startPositionUs shouldBe clip.start.inWholeMicroseconds
    video.endPositionUs shouldBe clip.gridEndUs(plan.output.frameRate)
    video.startsAtKeyFrame shouldBe false
    audio.startPositionUs shouldBe clip.start.inWholeMicroseconds
    audio.endPositionUs shouldBe clip.end.inWholeMicroseconds
    audio.startsAtKeyFrame shouldBe false
  }

  // Nothing is clipped away, so a configuration would only tell the extractor to seek somewhere it
  // already is.
  @Test
  fun `an untrimmed clip on the copy path carries no clipping configuration`() {
    val clip = clip(start = Duration.ZERO, end = SOURCE_LENGTH, startsAtKeyFrame = true)

    val item = composition(clip, ExportPath.Copy).firstItem()

    item.mediaItem.clippingConfiguration shouldBe MediaItem.ClippingConfiguration.UNSET
  }

  // The video's own cut, past which it either runs out of slots to cover or is held on its last frame. The shared grid
  // gives the slots' length, and the earlier of the source's own end and that length past the clip's start is the cut
  // regardless of which of those two the lowering's own branch picks, so a wrong branch there fails this rather than
  // being copied into it.
  private fun ResolvedClip.gridEndUs(rate: Int): Long {
    val slots = outputSlotsOf(span, rate)
    val length = outputSlotTime(slots.last + 1, rate) - outputSlotTime(slots.first, rate)
    return minOf(start + length, end).inWholeMicroseconds
  }

  private fun ResolvedComposition.firstItem(): EditedMediaItem =
    toMedia3(Media3FrameBounds.None)
      .sequences
      .first()
      .editedMediaItems
      .first()

  private fun composition(
    clip: ResolvedClip,
    path: ExportPath,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = listOf(clip),
          ),
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
      duration = clip.duration,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = path,
    )

  /**
   * A clip windowed inside a longer source, so neither bound sits on an end the lowering could hit
   * by ignoring one of them.
   */
  private fun clip(
    start: Duration = TRIM_START,
    end: Duration = TRIM_END,
    startsAtKeyFrame: Boolean,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("clip.mp4"),
      info =
        MediaInfo(
          duration = SOURCE_LENGTH,
          video =
            VideoTrackInfo(
              codedSize = Size(640, 360),
              displaySize = Size(640, 360),
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = AudioTrackInfo(codec = trackCodecOf("mp4a"), sampleRate = 48_000, channelCount = 2, bitrate = null),
          isExportable = true,
        ),
      start = start,
      end = end,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
      startsAtKeyFrame = startsAtKeyFrame,
      span = TimeRange.of(Duration.ZERO, end - start),
      sourceIndex = 0,
    )

  private companion object {
    val SOURCE_LENGTH = 12.seconds

    // Both clear of either end of the source, and of each other, so a lowering that dropped one
    // bound or swapped the two cannot land on the same window by accident. Neither is a whole
    // millisecond, so a window clipped in milliseconds reads differently from one in microseconds.
    val TRIM_START = 4_500.milliseconds + 250.microseconds
    val TRIM_END = 7_250.milliseconds + 750.microseconds
  }
}
