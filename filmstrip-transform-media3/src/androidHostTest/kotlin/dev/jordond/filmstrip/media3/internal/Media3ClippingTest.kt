package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration
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
  // Transformer decodes the leading group of pictures to reach it.
  @Test
  fun `a snapped trim carries its window and opens on a key frame`() {
    val clip = clip(startsAtKeyFrame = true)

    val clipping = assertNotNull(composition(clip, ExportPath.Transmux).firstItem().mediaItem.clippingConfiguration)

    clipping.startPositionMs shouldBe clip.start.inWholeMilliseconds
    clipping.endPositionMs shouldBe clip.end.inWholeMilliseconds
    clipping.startsAtKeyFrame shouldBe true
  }

  // The same window, without the snap. An export that re-encodes decodes to the cut anyway, so the
  // flag going along for the ride would move a trim the caller asked to land exactly.
  @Test
  fun `an unsnapped trim carries the same window and decodes to the cut`() {
    val clip = clip(startsAtKeyFrame = false)

    val clipping = assertNotNull(composition(clip, ExportPath.Transcode).firstItem().mediaItem.clippingConfiguration)

    clipping.startPositionMs shouldBe clip.start.inWholeMilliseconds
    clipping.endPositionMs shouldBe clip.end.inWholeMilliseconds
    clipping.startsAtKeyFrame shouldBe false
  }

  // Nothing is clipped away, so a configuration would only tell the extractor to seek somewhere it
  // already is.
  @Test
  fun `an untrimmed clip on the copy path carries no clipping configuration`() {
    val clip = clip(start = Duration.ZERO, end = SOURCE_LENGTH, startsAtKeyFrame = true)

    val item = composition(clip, ExportPath.Transmux).firstItem()

    item.mediaItem.clippingConfiguration shouldBe MediaItem.ClippingConfiguration.UNSET
  }

  private fun ResolvedComposition.firstItem(): EditedMediaItem =
    toMedia3()
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
      compositionGeometry = emptyList(),
      compositionInputSize = Size(640, 360),
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
      fit = Fit.Contain,
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
          audio = null,
          isExportable = true,
        ),
      start = start,
      end = end,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
      startsAtKeyFrame = startsAtKeyFrame,
      span = TimeRange.of(Duration.ZERO, end - start),
    )

  private companion object {
    val SOURCE_LENGTH = 12.seconds

    // Both clear of either end of the source, and of each other, so a lowering that dropped one
    // bound or swapped the two cannot land on the same window by accident.
    val TRIM_START = 4_500.milliseconds
    val TRIM_END = 7_250.milliseconds
  }
}
