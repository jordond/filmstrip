package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a still lowers onto a media3 item.
 *
 * media3 asks three things of an image that it never asks of a video: a duration to hold it for, a
 * rate to hold it at, and a MIME type to choose its image loader by. Each of them is read off the
 * resolved clip here rather than decided in the backend, and none of it needs a decoder, so the
 * whole file runs on a host with `android.net.Uri` stubbed out.
 */
class Media3ImageItemTest {
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
  fun `a still is held for the span the plan resolved`() {
    val composition = composition(clip(span = 3.seconds))

    val item = composition.firstItem()

    item.durationUs shouldBe 3.seconds.inWholeMicroseconds
    assertNotNull(item.mediaItem.localConfiguration).imageDurationMs shouldBe 3.seconds.inWholeMilliseconds
  }

  // The trim is collapsed into the span before the plan is handed over, so a still whose span is
  // shorter than the length it declares is held for the span and clipped by nothing.
  @Test
  fun `a trimmed still is held for its span and carries no clipping configuration`() {
    val composition = composition(clip(declared = 10.seconds, span = 4.seconds))

    val item = composition.firstItem()

    item.durationUs shouldBe 4.seconds.inWholeMicroseconds
    assertNotNull(item.mediaItem.localConfiguration).imageDurationMs shouldBe 4.seconds.inWholeMilliseconds
    item.mediaItem.clippingConfiguration shouldBe MediaItem.ClippingConfiguration.UNSET
  }

  // Read off the output rather than pinned to a number here, since the cadence a still is held at is
  // the one the whole composition is encoded at and the planner is what settles it.
  @Test
  fun `a still is held at the rate the output was resolved to`() {
    val composition = composition(clip(), frameRate = 24)

    composition.firstItem().frameRate shouldBe composition.output.frameRate
  }

  @Test
  fun `a still with no output rate is refused rather than lowered without one`() {
    val composition = composition(clip(), frameRate = null)

    assertFailsWith<Media3LoweringFailure> { composition.toMedia3() }
  }

  // Neither a cached copy of a byte buffer nor a content:// reference is guaranteed to carry a file
  // extension, and media3 falls back to guessing from one when the item names no type.
  @Test
  fun `the item names the format the still was read as`() {
    val composition = composition(clip(format = "png"))

    assertNotNull(composition.firstItem().mediaItem.localConfiguration).mimeType shouldBe "image/png"
  }

  // A frame reader picks its path off this and nothing else, so a run of clips is laid out and the
  // photo in the middle of it is the one that has to come back marked.
  @Test
  fun `only the photo in a run of clips is marked a still`() {
    val composition = composition(videoClip(), clip(span = 3.seconds), videoClip(), content = TrackContent.Video)

    val spans = composition.toMedia3Preview().spans

    spans.map { it.still } shouldBe listOf(false, true, false)
  }

  @Test
  fun `a still span covers the length the plan resolved for it`() {
    val composition = composition(videoClip(), clip(span = 3.seconds), videoClip(), content = TrackContent.Video)

    val photo = composition.toMedia3Preview().spans[1]

    photo.start shouldBe 1.seconds
    photo.end shouldBe 4.seconds
    photo.covers(2.seconds + 500.milliseconds) shouldBe true
  }

  private fun ResolvedComposition.firstItem(): EditedMediaItem =
    toMedia3()
      .sequences
      .first()
      .editedMediaItems
      .first()

  private fun composition(
    vararg clips: ResolvedClip,
    frameRate: Int? = 30,
    content: TrackContent = TrackContent.AudioAndVideo,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = content,
            looping = false,
            start = Duration.ZERO,
            clips = clips.toList(),
          ),
        ),
      compositionGeometry = emptyList(),
      compositionInputSize = Size(320, 180),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = Size(320, 180),
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = frameRate,
          audioFormat = null,
        ),
      layoutSize = Size(320, 180),
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = clips.fold(Duration.ZERO) { total, clip -> total + clip.duration },
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  /**
   * A still declared for [declared] and laid for [span], reported the way core's probe reports one.
   */
  private fun clip(
    declared: Duration = 3.seconds,
    span: Duration = declared,
    format: String = "jpeg",
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.Image(ImageSource.of("/fixtures/still.$format"), declared),
      info = imageMediaInfoOf(Size(640, 360), EXIF_ORIENTATION_NORMAL, format, declared),
      start = Duration.ZERO,
      end = span,
      effects = emptyList(),
      gain = 1f,
      startsAtKeyFrame = false,
    )

  /**
   * A one second video clip, for the runs a still has to be told apart from.
   */
  private fun videoClip(): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("clip.mp4"),
      info =
        MediaInfo(
          duration = 1.seconds,
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
      start = Duration.ZERO,
      end = 1.seconds,
      effects = emptyList(),
      gain = 1f,
      startsAtKeyFrame = false,
    )
}
