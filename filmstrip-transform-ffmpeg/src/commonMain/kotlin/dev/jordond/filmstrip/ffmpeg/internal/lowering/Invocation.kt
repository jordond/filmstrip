package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * One input the graph reads, in the order it is declared.
 *
 * @property source Where the bytes come from.
 * @property durationSeconds A hard duration for a generated input, which a silence source needs
 *   because it never ends on its own. With [startSeconds] set it counts from there rather than from
 *   the start of the source.
 * @property startSeconds Where in the source the input opens. This is an input seek, so a stream
 *   copy opens on the sync sample at or before it rather than decoding its way to the cut.
 * @property loopFrameRate The rate a still is repeated at, or null to read it once. It turns one
 *   image into a stream, which is what keeps a filter on that branch running and stops the merge
 *   downstream seeing the branch end.
 * @property rebased Whether the branch this input feeds ends on a `setpts` back to its own first
 *   frame, which leaves the branch reaching the merge at zero wherever the input opened. A scrubbed
 *   preview moves such an input to the scrub with `-itsoffset`.
 */
internal class InputSpec(
  val source: InputSource,
  val durationSeconds: Double? = null,
  val startSeconds: Double? = null,
  val loopFrameRate: Float? = null,
  val rebased: Boolean = false,
)

/**
 * What an [InputSpec] reads.
 *
 * [Generated] is `-f lavfi`, which is how a clip with no audio track contributes silence for
 * exactly as long as its video runs. Without it a concat of a silent clip and a clip with sound
 * drifts by the silent clip's length.
 */
internal sealed interface InputSource {
  /**
   * A file on disk.
   *
   * @property path The file ffmpeg opens.
   * @property description The caller's own name for the source, which an error about it reports. A source given as a
   * `file:` URI names the URI here rather than the path it was decoded to.
   */
  class OfPath(
    val path: String,
    val description: String = path,
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
 * @property filterGraph The whole graph, inlined into one argument of the argument list, which every
 *   ffmpeg from 8.0 to 9.0 reads the same way.
 * @property videoEncoder The libav encoder the device resolved for [OutputFormat.videoCodec], which
 *   is the one `-c:v` names. Null when there is no video track to write.
 * @property duration What the output runs to, which is what progress is a fraction of.
 * @property copy Whether this invocation remuxes the source streams rather than running a filter
 *   graph. True skips [filterGraph] and asks ffmpeg for `-c copy` instead of an encoder.
 * @property hdrTransfer The transfer function to tag the output with, or null to write SDR.
 * @property toneMapped Whether the graph brought an HDR grade down to BT.709. The tone-map nodes
 *   leave the frames in BT.709, so the output carries the tags that say so.
 * @property seekBase The source time composition time zero maps to, for a caller windowing this
 *   graph with an input seek. Null for a graph whose timeline an input seek cannot window, which is
 *   read forward from the start instead. Unused by an export, which renders the whole timeline.
 * @property videoSlots How many output slots the video covers, which is how many frames the graph
 *   writes. A copy writes the source's own frames out to a whole packet, so on a copy this is only
 *   how many of them a preview pumps. Null for an output with no video.
 * @property heldTail The output slots the graph holds the primary's last frame over, because an audio
 * track runs past it. Empty when nothing does, and for an output with no video.
 * @property sidecars The files [filterGraph] reads, which the caller writes down and hands back as
 * paths in this order. Each one is named in the graph by its placeholder until then.
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
  val toneMapped: Boolean = false,
  val seekBase: Duration? = null,
  val videoSlots: Long? = null,
  val heldTail: LongRange = LongRange.EMPTY,
  val sidecars: List<Sidecar> = emptyList(),
)

/**
 * The graph with every sidecar placeholder swapped for the path its bytes were written to.
 *
 * The path is escaped the way any other filter argument is, since a scratch directory is free to
 * carry a character the graph's own punctuation uses. Every placeholder is swapped in one pass over
 * the graph.
 */
internal fun Invocation.resolvedGraph(resolvedSidecars: List<String>): String {
  check(resolvedSidecars.size == sidecars.size) {
    "The graph reads ${sidecars.size} sidecars and ${resolvedSidecars.size} paths were resolved."
  }
  if (sidecars.isEmpty()) return filterGraph

  val paths =
    sidecars.mapIndexed { index, sidecar -> sidecar.placeholder to escapeFilterValue(resolvedSidecars[index]) }.toMap()

  return Regex(paths.keys.joinToString("|") { Regex.escape(it) }).replace(filterGraph) { paths.getValue(it.value) }
}

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
  resolvedSidecars: List<String>,
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
      if (input.source is InputSource.Generated) {
        add("-f")
        add("lavfi")
      }
      addAll(input.loopArguments())
      addAll(input.orientationArguments())
      // Ahead of -i, which is the seek the demuxer performs. After -i it would decode every frame
      // up to the cut and throw them away, which is the whole cost a snapped trim exists to avoid.
      input.startSeconds?.let {
        add("-ss")
        add(formatSeconds(it))
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
      add(resolvedGraph(resolvedSidecars))

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

/**
 * The demuxer options that turn a still into a stream, or nothing for an input read once.
 *
 * Both go ahead of `-i`, beside the seek and the bound, because they configure the demuxer rather
 * than the encoder. `-framerate` is the rate the repeated frames are stamped at, so a filter on the
 * branch runs once per output frame and a command written for one lands on it.
 */
internal fun InputSpec.loopArguments(): List<String> =
  loopFrameRate?.let { listOf("-loop", "1", "-framerate", it.toString()) }.orEmpty()

/**
 * `-noautorotate` for an overlay picture, or nothing for any other input.
 *
 * It keeps ffmpeg from turning the picture by an orientation tag it reads, which differs between builds, so the picture
 * opens as stored and the graph's own nodes lay it out the way core reads its tag.
 */
internal fun InputSpec.orientationArguments(): List<String> =
  if (source is InputSource.OfImage) listOf("-noautorotate") else emptyList()

private fun Invocation.videoArguments(): List<String> =
  buildList {
    // The capability probe named this encoder and the planner chose it, so an unnamed one means a
    // device was built by hand. This errors, so an export never encodes H264 into a file the
    // caller asked for something else in.
    val name = videoEncoder ?: error("The plan named no encoder for ${output.videoCodec}.")
    val encoder = ffmpegEncoderNamed(name)
    val hdrPixelFormat = hdrPixelFormatFor(name, hdrTransfer)
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
      val transfer = checkNotNull(hdrTransfer).ffmpegTag
      encoder?.hdrProfile?.let {
        add("-profile:v")
        add(it)
      }
      // hevc_videotoolbox ignores these and carries the source's own color properties across
      // instead, which lands on the same tags only because a kept grade is always the source's.
      add("-color_primaries")
      add(HDR_PRIMARIES)
      add("-colorspace")
      add(HDR_MATRIX)
      add("-color_trc")
      add(transfer)
      // The -color_* flags above tag the container. libx265 does not pick them up on its own, so
      // HDR10 signalling has to be repeated in its own params or a player reading the elementary
      // stream sees SDR.
      if (name == "libx265") {
        add("-x265-params")
        add(
          "colorprim=$HDR_PRIMARIES:transfer=$transfer:colormatrix=$HDR_MATRIX:" +
            "hdr10=1:repeat-headers=1",
        )
      }
    } else if (toneMapped) {
      // The tone-map nodes deliver BT.709 and say so on the frames they hand on, but only ffmpeg 7
      // and newer carries that onto the written stream. Tagging it here leaves the same file on
      // every build rather than one whose matrix reads `unknown` below that.
      add("-color_primaries")
      add("bt709")
      add("-colorspace")
      add("bt709")
      add("-color_trc")
      add("bt709")
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

internal fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

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
