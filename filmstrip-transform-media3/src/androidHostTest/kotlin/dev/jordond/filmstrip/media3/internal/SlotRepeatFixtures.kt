package dev.jordond.filmstrip.media3.internal

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
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.ClipWindow
import dev.jordond.filmstrip.media3.internal.bounds.FrameBounds
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

private val FRAME_SIZE = Size(320, 180)
private const val MICROS_PER_SECOND = 1_000_000L

/**
 * A video clip of [length] cut from the start of a source running at [sourceRate], laid at [at] and played at [speed].
 */
internal fun resolvedClip(
  sourceRate: Int,
  length: Duration,
  speed: Double = 1.0,
  at: Duration = Duration.ZERO,
  source: String = "clip.mp4",
): ResolvedClip =
  ResolvedClip(
    source = MediaSource.of(source),
    info =
      MediaInfo(
        duration = length + 1.seconds,
        video =
          VideoTrackInfo(
            codedSize = FRAME_SIZE,
            displaySize = FRAME_SIZE,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = sourceRate.toFloat(),
            codec = trackCodecOf("avc1"),
            bitDepth = 8,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = null,
            bitrate = null,
            hasAlpha = false,
          ),
        audioTracks = emptyList(),
        isExportable = true,
      ),
    start = Duration.ZERO,
    end = length,
    effects = emptyList(),
    gain = ResolvedGain.constant(1f, Duration.ZERO, outputLengthOf(length, speed)),
    startsAtKeyFrame = false,
    span = TimeRange.of(at, at + outputLengthOf(length, speed)),
    sourceIndex = 0,
    speed = speed,
  )

/**
 * Every sample of a source running at [sourceRate] for [seconds], and where its track ends.
 */
internal fun samplesAt(
  sourceRate: Int,
  seconds: Int,
): FrameBounds {
  val samples = (0L..sourceRate.toLong() * seconds).map { (it * MICROS_PER_SECOND / sourceRate).microseconds }
  return FrameBounds(samples, samples.last() + (MICROS_PER_SECOND / sourceRate).microseconds)
}

/**
 * The plan of [clips] on one track at [frameRate].
 */
internal fun resolvedTrack(
  clips: List<ResolvedClip>,
  frameRate: Int,
): ResolvedComposition =
  ResolvedComposition(
    tracks = listOf(ResolvedTrack(content = TrackContent.Video, looping = false, start = Duration.ZERO, clips = clips)),
    compositionEffects = emptyList(),
    output =
      OutputFormat(
        size = FRAME_SIZE,
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.None,
        bitrate = null,
        frameRate = frameRate,
        audioFormat = null,
      ),
    layoutSize = FRAME_SIZE,
    fill = Fill.Black,
    duration = clips.fold(Duration.ZERO) { total, clip -> total + clip.sourceDuration },
    hdr = ResolvedHdr.Keep,
    hdrTransfer = null,
    audio = AudioSpec.Keep,
    adjustments = emptyList(),
    path = ExportPath.Transcode,
  )

/**
 * What a driver reads for [clips], each one off [bounds].
 */
internal fun readsOf(
  clips: List<ResolvedClip>,
  bounds: FrameBounds,
): Media3ClipReads = Media3ClipReads(clips.associate { ClipWindow(it.source, it.start, it.end) to bounds })
