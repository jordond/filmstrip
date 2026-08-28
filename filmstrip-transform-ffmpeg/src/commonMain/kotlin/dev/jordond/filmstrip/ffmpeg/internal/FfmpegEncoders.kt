package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.VideoCodec

/**
 * One libav encoder this backend knows how to drive.
 *
 * @property name What `-c:v` names and what `ffmpeg -encoders` lists.
 * @property isHardwareAccelerated Whether the encode runs on dedicated silicon rather than the CPU.
 * @property preset The `-preset` to encode at, or null for an encoder that has no such option.
 * @property constantQualityArguments What this encoder needs to run at constant quality, used when
 *   the plan carries no bitrate.
 * @property probeArguments What the capability probe adds to keep its single frame cheap.
 * @property hdrPixelFormat The 10-bit pixel format this encoder writes HDR in, or null when it has
 *   no HDR profile to reach.
 * @property hdrProfile The `-profile:v` an HDR encode needs, or null alongside a null
 *   [hdrPixelFormat].
 */
internal class FfmpegEncoder(
  val name: String,
  val isHardwareAccelerated: Boolean,
  val preset: String? = null,
  val constantQualityArguments: List<String> = emptyList(),
  val probeArguments: List<String> = emptyList(),
  val hdrPixelFormat: String? = null,
  val hdrProfile: String? = null,
)

private const val ENCODE_PRESET = "medium"

// x264 and x265 are the only encoders here with a -preset, and ultrafast is what keeps a probe of
// one 8K frame from costing a second of somebody's startup.
private val X26X_PROBE = listOf("-preset", "ultrafast")

/**
 * Every encoder this backend drives, per codec, most preferred first.
 *
 * The order is measured rather than derived. On Apple silicon with ffmpeg 9.0.1, ten seconds of
 * 1080p and of 4K, wall clock:
 *
 * ```
 * libx264            0.62s / 1.79s       h264_videotoolbox  0.79s / 2.82s
 * hevc_videotoolbox  0.86s / 2.79s       libx265            2.55s / 8.20s
 * ```
 *
 * So H264 leads with the software encoder and HEVC with the hardware one. Acceleration is reported
 * per encoder and is not what this list sorts on. It is one machine and one clip, at a
 * pinned bitrate, with no quality-per-bit comparison behind it. nvenc, qsv and vaapi are absent
 * because nobody has measured them.
 */
internal val FFMPEG_ENCODERS: Map<VideoCodec, List<FfmpegEncoder>> =
  mapOf(
    VideoCodec.H264 to
      listOf(
        FfmpegEncoder(
          name = "libx264",
          isHardwareAccelerated = false,
          preset = ENCODE_PRESET,
          probeArguments = X26X_PROBE,
        ),
        FfmpegEncoder(name = "h264_videotoolbox", isHardwareAccelerated = true),
      ),
    VideoCodec.Hevc to
      listOf(
        FfmpegEncoder(
          name = "hevc_videotoolbox",
          isHardwareAccelerated = true,
          hdrPixelFormat = "p010le",
          hdrProfile = "main10",
        ),
        FfmpegEncoder(
          name = "libx265",
          isHardwareAccelerated = false,
          preset = ENCODE_PRESET,
          probeArguments = X26X_PROBE,
          hdrPixelFormat = "yuv420p10le",
          hdrProfile = "main10",
        ),
      ),
    VideoCodec.Vp9 to
      listOf(
        FfmpegEncoder(
          name = "libvpx-vp9",
          isHardwareAccelerated = false,
          // Without these libvpx picks CRF 32 itself and prints a line saying so, so filmstrip
          // asks for the same thing out loud. The bitrate has to be zeroed to reach that mode.
          constantQualityArguments = listOf("-crf", "32", "-b:v", "0"),
          probeArguments = listOf("-deadline", "realtime", "-cpu-used", "8"),
        ),
      ),
  )

/**
 * The order the planner walks video codecs in for [VideoCodec.Auto], most preferred first.
 *
 * Shorter than the encoder table on purpose: VP9 is encodable when the build carries libvpx, and it
 * is not something `Auto` should land on.
 */
internal val CODEC_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

/**
 * The encoders this build might be able to run for [this], most preferred first.
 */
internal fun VideoCodec.ffmpegEncoders(): List<FfmpegEncoder> = FFMPEG_ENCODERS[this].orEmpty()

/**
 * The encoder [name] refers to, or null for a name this backend does not know.
 */
internal fun ffmpegEncoderNamed(name: String): FfmpegEncoder? =
  FFMPEG_ENCODERS.values.firstNotNullOfOrNull { encoders -> encoders.firstOrNull { it.name == name } }
