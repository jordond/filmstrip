package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effect.WINDOW_CLOCK
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
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
 * @property decoderThreads How many threads the input's decoder runs on, or null to leave it at ffmpeg's own count. One
 *   holds a look's plan to fewer reference frames in memory and leaves the cores to the filters.
 */
internal class InputSpec(
  val source: InputSource,
  val durationSeconds: Double? = null,
  val startSeconds: Double? = null,
  val loopFrameRate: Float? = null,
  val rebased: Boolean = false,
  val decoderThreads: Int? = null,
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

  /**
   * Planes a feed writes over loopback while the graph runs.
   *
   * Compared by identity, since two clips reading equal planes still read them from clocks of their own.
   *
   * @property input What draws the planes.
   * @property origin Where this input's frames land in the composition, before a preview's window.
   */
  class Live(
    val input: LiveInput,
    val origin: Duration,
  ) : InputSource
}

/**
 * Everything needed to run one export, resolved before anything is spawned.
 *
 * @property filterGraph The whole graph. [readingGraphFrom] hands it to ffmpeg in a file, so its size has no bound from
 * the command line.
 * @property videoEncoder The libav encoder the device resolved for [OutputFormat.videoCodec], which
 *   is the one `-c:v` names. Null when there is no video track to write.
 * @property duration What the output runs to, which is what progress is a fraction of.
 * @property copy Whether this invocation remuxes the source streams rather than running a filter
 *   graph. True skips [filterGraph] and asks ffmpeg for `-c copy` instead of an encoder.
 * @property hdrTransfer The transfer function to tag the output with, or null to write SDR.
 * @property previewColor The nodes a preview's pump ends on, moving the one SD colour every clip of this plan keeps. A
 *   file displays that colour through its tags, and RGBA frames carry none. Empty for a plan keeping no SD colour.
 * @property seekBase The source time composition time zero maps to, for a caller windowing this
 *   graph with an input seek. Null for a graph whose timeline an input seek cannot window, which is
 *   read forward from the start instead. Unused by an export, which renders the whole timeline.
 * @property seekSpeed How fast the clip [seekBase] reads from plays, which turns a composition time into how far past
 *   [seekBase] the seek lands.
 * @property seekKeepsEarlierFrames Whether the seek keeps the frames ahead of where it lands, for a graph that reads
 *   them back off its own clock. An accurate seek drops them.
 * @property videoSlots How many output slots the video covers, which is how many frames the graph
 *   writes and a preview pumps. A copy writes [copyVideoPackets] instead. Null for an output with no video.
 * @property copyVideoPackets How many video packets a copy writes in decode order, from
 *   [dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf]. Null for a copy that runs to the source's end, and
 *   for anything that is not a copy.
 * @property copyAudioTrack Which of the source's audio streams a copy writes, counted among its audio streams from
 *   zero, or null when it writes none.
 * @property heldTail The output slots the graph holds the primary's last frame over, because an audio
 * track runs past it. Empty when nothing does, and for an output with no video.
 * @property sidecars The files [filterGraph] reads, which the caller writes down and hands back as
 * paths in this order. Each one is named in the graph by its placeholder until then.
 * @property liveForm This plan with its look's maps read from live planes, for a caller that can bind them. Null when
 * the plan carries none, or when [liveFallback] names why it cannot run them.
 * @property liveFallback Why a plan carrying live planes draws them with this graph instead, for the diagnostics.
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
  val previewColor: List<FilterNode> = emptyList(),
  val seekBase: Duration? = null,
  val seekSpeed: Double = 1.0,
  val seekKeepsEarlierFrames: Boolean = seekSpeed != 1.0,
  val videoSlots: Long? = null,
  val copyVideoPackets: Int? = null,
  val copyAudioTrack: Int? = null,
  val heldTail: LongRange = LongRange.EMPTY,
  val sidecars: List<Sidecar> = emptyList(),
  val liveForm: Invocation? = null,
  val liveFallback: String? = null,
)

/**
 * The graph with every sidecar placeholder swapped for the path its bytes were written to.
 *
 * The path is escaped the way any other filter argument is, since a scratch directory is free to
 * carry a character the graph's own punctuation uses. Every placeholder is swapped in one pass over
 * the graph.
 */
internal fun Invocation.resolvedGraph(
  resolvedSidecars: List<String>,
  windowOpensAt: Duration = Duration.ZERO,
): String {
  check(resolvedSidecars.size == sidecars.size) {
    "The graph reads ${sidecars.size} sidecars and ${resolvedSidecars.size} paths were resolved."
  }
  val clocked =
    filterGraph
      .replace(WINDOW_CLOCK, formatSeconds(windowOpensAt.toDouble(DurationUnit.SECONDS)))
      .withLiveStarts(inputs, windowOpensAt)
  if (sidecars.isEmpty()) return clocked

  val paths =
    sidecars.mapIndexed { index, sidecar -> sidecar.placeholder to escapeFilterValue(resolvedSidecars[index]) }.toMap()

  return Regex(paths.keys.joinToString("|") { Regex.escape(it) }).replace(clocked) { paths.getValue(it.value) }
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
      addAll(input.decoderArguments())
      addAll(input.liveArguments())
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

    // A copy remuxes the source's video and its one chosen audio stream untouched, so there is no graph to run and no
    // encoder to name. The video map is optional because ffmpeg refuses a map that matches nothing, and a source with
    // no audio track maps none.
    if (copy) {
      add("-map")
      add("0:v?")
      copyAudioTrack?.let {
        add("-map")
        add("0:a:$it")
      }
      add("-c")
      add("copy")
      // Drops the video packets past the count rather than stopping there, since -frames:v ends the whole file and
      // cuts the audio short of the input bound it still runs to.
      copyVideoPackets?.let {
        add("-bsf:v")
        add("$PACKET_DROP_FILTER=drop=gte(n\\,$it)")
      }
    } else {
      add(GRAPH_FLAG)
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
 * This command with its graph moved into [scratch] and read back with `-/filter_complex`.
 *
 * A file has no size bound, where Windows caps a whole command line and Linux caps one argument. The command this is
 * called on still holds the graph's text, which is what a diagnostics report shows.
 *
 * @throws kotlinx.io.IOException When the file cannot be written.
 */
internal fun List<String>.readingGraphFrom(scratch: Scratch): List<String> {
  val flag = indices.firstOrNull { this[it] == GRAPH_FLAG && getOrNull(it - 1) != "-i" } ?: return this
  val path = scratch.write(this[flag + 1].encodeToByteArray(), GRAPH_EXTENSION)
  return take(flag) + GRAPH_FILE_FLAG + path + drop(flag + 2)
}

/**
 * The demuxer options that turn a still into a stream, or nothing for an input read once.
 *
 * Both go ahead of `-i`, beside the seek and the bound, because they configure the demuxer rather
 * than the encoder. `-framerate` is the rate the repeated frames are stamped at, so a filter on the
 * branch runs once per output frame and a per-frame expression is read at every frame's own time.
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

/**
 * `-threads` for an input whose decoder is pinned to a count, or nothing for any other input.
 *
 * Ahead of `-i` it reaches the decoder, where the same flag after the maps reaches only the encoder.
 */
internal fun InputSpec.decoderArguments(): List<String> =
  decoderThreads?.let { listOf("-threads", it.toString()) }.orEmpty()

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
    add(writtenPixelFormat(hdrPixelFormat))
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
    }
    if (name == "libx265") {
      x265Params().takeIf { it.isNotEmpty() }?.let {
        add("-x265-params")
        add(it.joinToString(":"))
      }
    }
    val bitrate = checkNotNull(output.bitrate) { "The planner settles a bitrate on every transcode." }.bitsPerSecond
    add("-b:v")
    add(bitrate.toString())
    add("-maxrate")
    add((bitrate * MAXRATE_NUMERATOR / MAXRATE_DENOMINATOR).toString())
    add("-bufsize")
    add((bitrate * BUFSIZE_FACTOR).toString())
    // No -r and no rotation metadata: the graph's own fps filter already pins the rate, and
    // filmstrip bakes rotation into the pixels rather than asking a player to apply it.
  }

private fun Invocation.x265Params(): List<String> =
  buildList {
    // The -color_* flags tag the container. libx265 does not pick them up on its own, so HDR10 signalling has to be
    // repeated in its own params or a player reading the elementary stream sees SDR.
    hdrTransfer?.let {
      add("colorprim=$HDR_PRIMARIES")
      add("transfer=${it.ffmpegTag}")
      add("colormatrix=$HDR_MATRIX")
      add("hdr10=1")
      add("repeat-headers=1")
    }
    if (output.size.width in X265_CUTREE_CRASH_WIDTHS) add("cutree=0")
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

// A limit of libx265 itself. Version 4.1 corrupts its heap and aborts on a frame this wide, at any height, rate or bit
// depth, unless cutree is off. 4.3 encodes these widths either way.
private val X265_CUTREE_CRASH_WIDTHS = 34..48

internal const val GRAPH_FLAG = "-filter_complex"
private const val GRAPH_FILE_FLAG = "-/filter_complex"
private const val GRAPH_EXTENSION = "filtergraph"
private const val MICROS_PER_SECOND = 1_000_000.0
private const val MICRO_DIGITS = 6
private const val MAXRATE_NUMERATOR = 3L
private const val MAXRATE_DENOMINATOR = 2L
private const val BUFSIZE_FACTOR = 2L
private const val AUDIO_BITRATE = "192k"
