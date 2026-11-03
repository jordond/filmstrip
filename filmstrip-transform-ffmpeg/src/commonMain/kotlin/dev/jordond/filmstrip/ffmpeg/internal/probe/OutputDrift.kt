package dev.jordond.filmstrip.ffmpeg.internal.probe

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.videoCodecOf
import kotlin.math.abs

/**
 * How the file ffmpeg wrote differs from what [plan] asked for, or null when it matches.
 *
 * ffmpeg falls back silently when it cannot honour a request, so a run that exited cleanly is read
 * back and compared on its tracks, frame, codecs, frame rate and audio format.
 */
internal fun drift(
  plan: ExportPlan,
  written: MediaInfo,
): String? {
  val expected = plan.output
  // AudioSpec.AudioOnly still resolves a videoCodec and size, since OutputFormat has no way to
  // say there is none, but this backend never writes a video track for it. Comparing against a
  // track the plan was never going to produce would fail every audio-only export.
  if (plan.composition.audio == AudioSpec.AudioOnly) {
    if (written.video != null) return "The written file has a video track, and the plan asked for audio only."
  } else {
    val video = written.video
    if (video == null) return "The written file has no video track, but the plan asked for one."
    if (video.displaySize != expected.size) {
      return "The written file is ${video.displaySize.width}x${video.displaySize.height}, and the " +
        "plan asked for ${expected.size.width}x${expected.size.height}."
    }
    // Only a codec CodecKind actually names is compared. A kind it does not recognise, such as
    // ALAC, cannot be checked either way, and treating that as drift would fail a file that
    // matches the plan exactly.
    val writtenVideoCodec = videoCodecOf(video.codec.kind)
    if (writtenVideoCodec != null && writtenVideoCodec != expected.videoCodec) {
      return "The written file's video codec is ${video.codec.name}, and the plan asked for ${expected.videoCodec}."
    }
    val actual = video.frameRate
    if (actual != null && abs(actual - expected.frameRate) > FRAME_RATE_TOLERANCE) {
      return "The written file runs at $actual fps, and the plan asked for ${expected.frameRate}."
    }
  }
  expected.audioFormat?.let { planned ->
    val audio = written.audio ?: return "The written file has no audio track, but the plan asked for one."
    if (audio.sampleRate != planned.sampleRate || audio.channelCount != planned.channelCount) {
      return "The written file's audio is ${audio.sampleRate} Hz with ${audio.channelCount} " +
        "channels, and the plan asked for ${planned.sampleRate} Hz with ${planned.channelCount}."
    }
    val writtenAudioCodec = audioCodecOf(audio.codec.kind)
    if (writtenAudioCodec != null && writtenAudioCodec != expected.audioCodec) {
      return "The written file's audio codec is ${audio.codec.name}, and the plan asked for ${expected.audioCodec}."
    }
  }
  return null
}

private const val FRAME_RATE_TOLERANCE = 0.5f
