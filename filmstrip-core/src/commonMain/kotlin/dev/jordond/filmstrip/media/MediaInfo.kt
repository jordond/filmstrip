package dev.jordond.filmstrip.media

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Everything filmstrip can learn about a source without decoding it.
 *
 * Read it before planning an edit. Pair it with [DeviceCapabilities] to know whether this device
 * can handle the source.
 *
 * @property duration How long the source runs.
 * @property video The video track, or null when the source has none.
 * @property audio The audio track, or null when the source has none.
 * @property isExportable False for DRM-protected assets and anything else the platform refuses to
 *   export. Such a source can neither be exported nor previewed, so check it first.
 */
@Serializable
@Poko
public class MediaInfo(
  public val duration: Duration,
  public val video: VideoTrackInfo?,
  public val audio: AudioTrackInfo?,
  public val isExportable: Boolean,
)

/**
 * The video track of a source.
 *
 * @property codedSize Storage dimensions, before rotation and pixel-aspect correction.
 * @property displaySize How the frame should look on screen, with [rotationDegrees] and
 *   [pixelAspectRatio] applied. Size a target from this, not from [codedSize].
 * @property rotationDegrees Rotation the container asks a player to apply: 0, 90, 180 or 270.
 * @property pixelAspectRatio Width of a stored pixel over its height, `1f` for square pixels.
 * @property frameRate Frames per second, or null when the container does not say.
 * @property codec What the track is encoded with.
 * @property bitDepth Bits per colour channel, 8 for SDR and 10 for most HDR, or null when the
 *   track does not say. Expect null often. Only HEVC carries the depth somewhere every backend can
 *   read it, so an H.264 track answers null on every platform.
 * @property colorSpace The track's colour space, or [ColorSpace.Unknown] for untagged media.
 * @property hdrTransfer The HDR transfer function, or null for SDR.
 * @property bitrate The track's bitrate, or null when the container does not report one.
 */
@Serializable
@Poko
public class VideoTrackInfo(
  public val codedSize: Size,
  public val displaySize: Size,
  public val rotationDegrees: Int,
  public val pixelAspectRatio: Float,
  public val frameRate: Float?,
  public val codec: TrackCodec,
  public val bitDepth: Int?,
  public val colorSpace: ColorSpace,
  public val hdrTransfer: HdrTransfer?,
  public val bitrate: Bitrate?,
)

/**
 * The audio track of a source.
 *
 * @property codec What the track is encoded with.
 * @property sampleRate Samples per second, in hertz.
 * @property channelCount How many channels the track carries.
 * @property bitrate The track's bitrate, or null when the container does not report one.
 */
@Serializable
@Poko
public class AudioTrackInfo(
  public val codec: TrackCodec,
  public val sampleRate: Int,
  public val channelCount: Int,
  public val bitrate: Bitrate?,
)

/**
 * What a track is encoded with, both as the platform spells it and as filmstrip recognises it.
 *
 * A four-character code is what an ISO container stores and what AVFoundation reports, a MIME type
 * is what Android's extractor reports, and ffmpeg reports either depending on whether the container
 * carried a tag. Branch on [kind]. Show [name] to a human or put it in a bug report.
 *
 * @property name The platform's own spelling, such as `hvc1`, `video/hevc` or `hevc`. Empty when
 *   the platform did not name it.
 * @property kind What filmstrip made of [name], or [CodecKind.Other] for anything it does not
 *   recognise.
 */
@Serializable
@Poko
public class TrackCodec(
  public val name: String,
  public val kind: CodecKind,
)

/**
 * A codec filmstrip recognises.
 *
 * Covers video and audio alike. Which media type a codec belongs to is already settled by whether
 * it arrived on a [VideoTrackInfo] or an [AudioTrackInfo].
 */
@Serializable
public enum class CodecKind {
  H264,
  Hevc,
  Vp8,
  Vp9,
  Av1,
  Aac,
  Opus,
  Vorbis,
  Flac,
  Mp3,
  Pcm,

  /**
   * A codec filmstrip does not recognise. Read [TrackCodec.name] to find out what it was.
   */
  Other,
}

/**
 * A colour space, as far as both platforms agree on one.
 *
 * Describes what a source is. The composition declares the space its output is rendered in.
 */
@Serializable
public enum class ColorSpace {
  Bt601,
  Bt709,
  Bt2020,
  Unknown,
}

/**
 * An HDR transfer function.
 */
@Serializable
public enum class HdrTransfer {
  Hlg,
  Pq,
}

/**
 * The result of probing a source.
 *
 * An unreadable file is a [Failure] arm, never a thrown exception, so branch on it.
 */
public sealed interface ProbeResult {
  /**
   * The source was read.
   *
   * @property info What the source turned out to be.
   */
  @Poko
  public class Success(
    public val info: MediaInfo,
  ) : ProbeResult

  /**
   * The source could not be read.
   *
   * @property error Why the read failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : ProbeResult
}
