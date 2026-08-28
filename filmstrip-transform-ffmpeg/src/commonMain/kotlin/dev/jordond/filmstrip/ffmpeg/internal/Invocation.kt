package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlin.time.Duration

/**
 * One input the graph reads, in the order it is declared.
 *
 * @property source Where the bytes come from.
 * @property loop Whether the input repeats. This is an input option rather than a filter: the
 *   `loop` and `aloop` filters buffer the decoded media in memory, which for a music bed is the
 *   whole track.
 * @property durationSeconds A hard duration for a generated input, which a silence source needs
 *   because it never ends on its own.
 */
internal class InputSpec(
  val source: InputSource,
  val loop: Boolean = false,
  val durationSeconds: Double? = null,
)

/**
 * What an [InputSpec] reads.
 *
 * [Generated] is `-f lavfi`, which is how a clip with no audio track contributes silence for
 * exactly as long as its video runs. Without it a concat of a silent clip and a clip with sound
 * drifts by the silent clip's length.
 */
internal sealed interface InputSource {
  class OfPath(
    val path: String,
  ) : InputSource

  class OfImage(
    val image: ImageSource,
  ) : InputSource

  class Generated(
    val description: String,
  ) : InputSource
}

/**
 * Everything needed to run one export, resolved before anything is spawned.
 *
 * @property filterGraph The whole graph, inlined into one argument. The file forms exist only to
 *   dodge a shell and a command-line length limit, and their spelling changed twice between 4.4 and
 *   9.0, so an argument list avoids the problem rather than working around it.
 * @property videoEncoder The libav encoder the device resolved for [OutputFormat.videoCodec], which
 *   is the one `-c:v` names. Null when there is no video track to write.
 * @property duration What the output runs to, which is what progress is a fraction of.
 * @property copy Whether this invocation remuxes the source streams rather than running a filter
 *   graph. True skips [filterGraph] and asks ffmpeg for `-c copy` instead of an encoder.
 * @property hdrTransfer The transfer function to tag the output with, or null to write SDR.
 */
internal class Invocation(
  val inputs: List<InputSpec>,
  val filterGraph: String,
  val videoLabel: String?,
  val audioLabel: String?,
  val output: OutputFormat,
  val videoEncoder: String?,
  val duration: Duration,
  val copy: Boolean = false,
  val hdrTransfer: HdrTransfer? = null,
)

/**
 * Builds the argument list.
 *
 * An array, never a command line. FFmpeg documents three levels of escaping and the third is the
 * shell, so not having a shell removes a level, and it removes Windows argument quoting with it.
 */
internal fun Invocation.arguments(
  toolchain: Toolchain,
  config: FfmpegConfig,
  resolvedInputs: List<String>,
  outputPath: String,
): List<String> =
  buildList {
    add(toolchain.ffmpeg)
    add("-hide_banner")
    add("-loglevel")
    add("error")
    add("-nostats")
    // Machine-readable progress on stdout, diagnostics on stderr, and no -nostdin: stdin is a pipe
    // filmstrip owns and `q` on it is how the export is stopped cleanly.
    add("-progress")
    add("pipe:1")
    add("-stats_period")
    add("0.25")
    add("-y")

    inputs.forEachIndexed { index, input ->
      if (input.loop) {
        add("-stream_loop")
        add("-1")
      }
      if (input.source is InputSource.Generated) {
        add("-f")
        add("lavfi")
      }
      input.durationSeconds?.let {
        add("-t")
        add(formatSeconds(it))
      }
      add("-i")
      add(resolvedInputs[index])
    }

    // A copy remuxes the source's video and audio streams untouched, so there is no graph to run
    // and no encoder to name. The maps are optional because ffmpeg refuses a map that matches
    // nothing, and a source with no audio track is a copy this backend allows.
    if (copy) {
      add("-map")
      add("0:v?")
      add("-map")
      add("0:a?")
      add("-c")
      add("copy")
    } else {
      add("-filter_complex")
      add(filterGraph)

      videoLabel?.let {
        add("-map")
        add("[$it]")
      } ?: add("-vn")

      audioLabel?.let {
        add("-map")
        add("[$it]")
      } ?: add("-an")

      if (videoLabel != null) addAll(videoArguments())
      if (audioLabel != null) addAll(audioArguments())
    }

    config.threads?.let {
      add("-threads")
      add(it.toString())
    }

    add("-movflags")
    add("+faststart")

    addAll(config.extraArgs)
    add(outputPath)
  }

private fun Invocation.videoArguments(): List<String> =
  buildList {
    // The capability probe named this encoder and the planner chose it, so an unnamed one means a
    // device was built by hand. Erroring rather than falling through to libx264 is what stops an
    // export encoding H264 into a file the caller asked for something else in.
    val name = videoEncoder ?: error("The plan named no encoder for ${output.videoCodec}.")
    val encoder = ffmpegEncoderNamed(name)
    val hdrPixelFormat = hdrTransfer?.let { encoder?.hdrPixelFormat }
    add("-c:v")
    add(name)
    add("-pix_fmt")
    add(hdrPixelFormat ?: "yuv420p")
    encoder?.preset?.let {
      add("-preset")
      add(it)
    }
    // QuickTime plays hvc1 and refuses hev1, which is what an untagged HEVC stream is written as.
    if (output.videoCodec == VideoCodec.Hevc) {
      add("-tag:v")
      add("hvc1")
    }
    if (hdrPixelFormat != null) {
      val transfer = if (hdrTransfer == HdrTransfer.Pq) "smpte2084" else "arib-std-b67"
      encoder?.hdrProfile?.let {
        add("-profile:v")
        add(it)
      }
      // hevc_videotoolbox ignores these and carries the source's own colour properties across
      // instead, which lands on the same tags only because a kept grade is always the source's.
      // FfmpegExportTest reads the written file back rather than trusting the flags.
      add("-color_primaries")
      add("bt2020")
      add("-colorspace")
      add("bt2020nc")
      add("-color_trc")
      add(transfer)
      // The -color_* flags above tag the container. libx265 does not pick them up on its own, so
      // HDR10 signalling has to be repeated in its own params or a player reading the elementary
      // stream sees SDR.
      if (name == "libx265") {
        add("-x265-params")
        add("colorprim=bt2020:transfer=$transfer:colormatrix=bt2020nc:hdr10=1:repeat-headers=1")
      }
    }
    output.bitrate?.let {
      add("-b:v")
      add(it.bitsPerSecond.toString())
      add("-maxrate")
      add((it.bitsPerSecond * MAXRATE_NUMERATOR / MAXRATE_DENOMINATOR).toString())
      add("-bufsize")
      add((it.bitsPerSecond * BUFSIZE_FACTOR).toString())
    } ?: addAll(encoder?.constantQualityArguments.orEmpty())
    // No -r and no rotation metadata: the graph's own fps filter already pins the rate, and
    // filmstrip bakes rotation into the pixels rather than asking a player to apply it.
  }

private fun Invocation.audioArguments(): List<String> =
  buildList {
    val format = output.audioFormat ?: return@buildList
    add("-c:a")
    add(
      when (output.audioCodec) {
        AudioCodec.Alac -> "alac"
        AudioCodec.Aac -> "aac"
        else -> error("The plan named no encoder for ${output.audioCodec}.")
      },
    )
    add("-b:a")
    add(AUDIO_BITRATE)
    add("-ar")
    add(format.sampleRate.toString())
    add("-ac")
    add(format.channelCount.toString())
  }

internal fun formatSeconds(value: Double): String {
  val micros = (value * MICROS_PER_SECOND).toLong().coerceAtLeast(0L)
  val whole = micros / MICROS_PER_SECOND.toLong()
  val fraction = micros % MICROS_PER_SECOND.toLong()
  return "$whole." + fraction.toString().padStart(MICRO_DIGITS, '0')
}

private const val MICROS_PER_SECOND = 1_000_000.0
private const val MICRO_DIGITS = 6
private const val MAXRATE_NUMERATOR = 3L
private const val MAXRATE_DENOMINATOR = 2L
private const val BUFSIZE_FACTOR = 2L
private const val AUDIO_BITRATE = "192k"
