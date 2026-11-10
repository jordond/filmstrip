package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Clips, tracks and plans built the way a planner lays them, for the internal lowering tests that hand
// one piece of the lowering exactly what the plan would.

/**
 * A clip reading `[from, from + length)` of [source], laid on the timeline at [at].
 */
internal fun laidClip(
  source: MediaSource,
  at: Duration,
  length: Duration,
  from: Duration = Duration.ZERO,
  gain: ResolvedGain = ResolvedGain.constant(1f, Duration.ZERO, length),
  size: Size = Size(640, 360),
): ResolvedClip =
  ResolvedClip(
    source = source,
    info =
      MediaInfo(
        duration = 2.seconds,
        video =
          VideoTrackInfo(
            codedSize = size,
            displaySize = size,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = 30f,
            codec = trackCodecOf("avc1"),
            bitDepth = 8,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = null,
            bitrate = null,
            hasAlpha = false,
          ),
        audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
        isExportable = true,
      ),
    start = from,
    end = from + length,
    effects = emptyList(),
    gain = gain,
    startsAtKeyFrame = false,
    span = TimeRange.of(at, at + length),
    sourceIndex = 0,
  )

/**
 * [clips] laid end to end from [start] on one track of [content].
 */
internal fun laidTrack(
  content: TrackContent,
  start: Duration,
  vararg lengths: Pair<MediaSource, Duration>,
): ResolvedTrack {
  var at = start
  val clips = lengths.map { (source, length) -> laidClip(source, at, length).also { at += length } }
  return ResolvedTrack(content = content, looping = false, start = start, clips = clips)
}

/**
 * A transcode of [tracks] into a 320 by 180 output at [frameRate], running for [duration].
 */
internal fun laidPlan(
  tracks: List<ResolvedTrack>,
  duration: Duration,
  frameRate: Int = 30,
  path: ExportPath = ExportPath.Transcode,
  hdrTransfer: HdrTransfer? = null,
): ResolvedComposition {
  val output = Size(320, 180)
  return ResolvedComposition(
    tracks = tracks,
    compositionEffects = emptyList(),
    output =
      OutputFormat(
        size = output,
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.Aac,
        bitrate = null,
        frameRate = frameRate,
        audioFormat = null,
      ),
    layoutSize = output,
    fill = Fill.Black,
    duration = duration,
    hdr = ResolvedHdr.Keep,
    hdrTransfer = hdrTransfer,
    path = path,
    audio = AudioSpec.Keep,
    adjustments = emptyList(),
  )
}
