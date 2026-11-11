package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Makes the [MediaInfo] of a ten second 1080p H.264 file with one stereo AAC track.
 *
 * A test names only the fields it is about. A source with no sound passes an empty [audioTracks], and one with no
 * picture passes a null [video].
 *
 * @param duration How long the source plays for.
 * @param video The video track, or null for a source with none.
 * @param audioTracks Every audio track, in container order.
 * @param isExportable Whether the platform would export the source.
 * @return The info, as a probe would report it.
 */
public fun testMediaInfo(
  duration: Duration = 10.seconds,
  video: VideoTrackInfo? = testVideoTrack(),
  audioTracks: List<AudioTrackInfo> = listOf(testAudioTrack()),
  isExportable: Boolean = true,
): MediaInfo = MediaInfo(duration, video, audioTracks, isExportable)

/**
 * Makes a 1080p, 30 frames per second, SDR H.264 video track.
 *
 * A larger source passes its own [displaySize], such as `Size(3840, 2160)`. The stored size follows the display
 * size, turned on its side when [rotationDegrees] is 90 or 270.
 *
 * @param displaySize How the frame looks on screen, with the rotation applied.
 * @param rotationDegrees The rotation the container asks for.
 * @param codedSize The stored dimensions, before rotation.
 * @param pixelAspectRatio Width of a stored pixel over its height.
 * @param frameRate Frames per second, or null when the container does not say.
 * @param codec What the track is encoded with.
 * @param bitDepth Bits per color channel, or null when the track does not say.
 * @param colorSpace The track's color space.
 * @param hdrTransfer The HDR transfer function, or null for SDR.
 * @param bitrate The track's bitrate, or null when the container does not report one.
 * @param hasAlpha Whether the pictures can carry transparent pixels.
 * @return The track, as a probe would report it.
 */
public fun testVideoTrack(
  displaySize: Size = Size(1920, 1080),
  rotationDegrees: Int = 0,
  codedSize: Size = if (rotationDegrees % 180 == 0) displaySize else Size(displaySize.height, displaySize.width),
  pixelAspectRatio: Float = 1f,
  frameRate: Float? = 30f,
  codec: TrackCodec = TrackCodec("avc1", CodecKind.H264),
  bitDepth: Int? = null,
  colorSpace: ColorSpace = ColorSpace.Bt709,
  hdrTransfer: HdrTransfer? = null,
  bitrate: Bitrate? = Bitrate.mbps(8),
  hasAlpha: Boolean = false,
): VideoTrackInfo =
  VideoTrackInfo(
    codedSize = codedSize,
    displaySize = displaySize,
    rotationDegrees = rotationDegrees,
    pixelAspectRatio = pixelAspectRatio,
    frameRate = frameRate,
    codec = codec,
    bitDepth = bitDepth,
    colorSpace = colorSpace,
    hdrTransfer = hdrTransfer,
    bitrate = bitrate,
    hasAlpha = hasAlpha,
  )

/**
 * Makes a stereo 48 kHz AAC audio track, flagged as the one to play.
 *
 * @param id The name a clip plays this track by.
 * @param codec What the track is encoded with.
 * @param sampleRate Samples per second, in hertz.
 * @param channelCount How many channels the track carries.
 * @param bitrate The track's bitrate, or null when the container does not report one.
 * @param language The track's language as an ISO 639-2 code, or null when unset.
 * @param isDefault Whether the container flags this track as the one to play.
 * @return The track, as a probe would report it.
 */
public fun testAudioTrack(
  id: AudioTrackId = AudioTrackId(1),
  codec: TrackCodec = TrackCodec("mp4a", CodecKind.Aac),
  sampleRate: Int = 48_000,
  channelCount: Int = 2,
  bitrate: Bitrate? = Bitrate.kbps(128),
  language: String? = null,
  isDefault: Boolean = true,
): AudioTrackInfo = AudioTrackInfo(id, codec, sampleRate, channelCount, bitrate, language, isDefault)

/**
 * Makes a source that stands for a file called [name].
 *
 * Nothing is read from it. Two sources made with the same name are equal, so a test scripts an answer for one and
 * asserts on the other.
 *
 * @param name What to call the file, without an extension.
 * @return A path source under a folder that does not exist.
 */
public fun testMediaSource(name: String = "clip"): MediaSource = MediaSource.of("/filmstrip-testing/$name.mp4")

/**
 * Makes a sink that stands for an output file called [name].
 *
 * Nothing is written to it. Two sinks made with the same name are equal.
 *
 * @param name What to call the file, without an extension.
 * @return A path sink under a folder that does not exist.
 */
public fun testMediaSink(name: String = "export"): MediaSink = MediaSink.of("/filmstrip-testing/$name.mp4")
