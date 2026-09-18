package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What the pump reads off the pipe, which is what
 * [dev.jordond.filmstrip.player.ReadbackFrame.pixels] is documented to carry.
 */
internal const val PREVIEW_PIXEL_FORMAT: String = "rgba"

internal const val PREVIEW_CHANNELS: Int = 4

/**
 * Builds the argument list for the preview pump.
 *
 * A sibling of [arguments] rather than a fork of it. The graph, its labels and its inputs are the
 * ones [GraphLowering] already wrote, so a previewed frame and an exported one come out of one
 * filter chain. What differs is the window, `-ss` ahead of the first input, and the sink, raw RGBA
 * frames on stdout rather than a container on disk.
 *
 * `-copyts -start_at_zero` hold the source's own timestamps in front of the graph. Without them
 * ffmpeg rebases a seeked input to zero and every trim the graph carries shifts by the seek. With
 * no seek they leave the timestamps exactly where an export sees them.
 *
 * The clip carries the seek. An overlay image seeked into delivers nothing at all, so a branch
 * [InputSpec.rebased] marks is carried to the same point by `-itsoffset` instead: its timestamps
 * move to the composition times the scrub lands on, and the `setpts` that branch ends on takes the
 * move back off before the merge lines the frames up.
 *
 * [FfmpegConfig.extraArgs] does not reach here. It is documented as going to every encode, and this
 * runs none.
 *
 * @param at Where in the composition the first frame comes from. Ignored when [Invocation.seekBase]
 *   is null, since that timeline cannot be windowed by an input seek and is read forward instead.
 */
internal fun Invocation.previewArguments(
  toolchain: Toolchain,
  config: FfmpegConfig,
  resolvedInputs: List<String>,
  resolvedSidecars: List<String>,
  at: Duration,
): List<String> =
  buildList {
    add(toolchain.ffmpeg)
    add("-hide_banner")
    add("-loglevel")
    add("error")
    add("-nostats")
    // Nothing is written to the child's stdin, and a child that inherits the console steals the
    // keystrokes a host application is reading.
    add("-nostdin")
    add("-copyts")
    add("-start_at_zero")

    val seek = seekBase?.plus(at)?.positiveSeconds()
    // What a rebased branch has to be moved by to land where the seek does. The clip's branch opens
    // with setpts=PTS-STARTPTS, so its clock reads zero at the seek whatever the seek was, and a
    // branch left at its own start would be read against that zero from the top. The move lands on
    // the frame the seek delivers rather than on [at] itself, which is the frame after it wherever
    // the scrub falls inside a frame period.
    val branchOffset = at.takeIf { seekBase != null && it > Duration.ZERO }?.onFrameGrid(output.frameRate)
    inputs.forEachIndexed { index, input ->
      // Only the first input carries the seek. It is the clip the graph windows, and an overlay
      // image or a silence filler seeked into delivers nothing at all.
      if (index == 0 && seek != null) {
        add("-ss")
        add(formatSeconds(seek))
      }
      // So the overlay branch is moved rather than seeked. Its own frames still start where the
      // demuxer makes them, and the offset puts them at the composition times the scrub lands on,
      // which is the clock a sendcmd sidecar's intervals are written against. GraphLowering closes
      // the branch with a setpts that takes the offset back off before the merge reads it.
      if (branchOffset != null && input.rebased) {
        add("-itsoffset")
        add(formatSeconds(branchOffset))
      }
      if (input.source is InputSource.Generated) {
        add("-f")
        add("lavfi")
      }
      addAll(input.loopArguments())
      // The seek has already eaten [at] of the window the input bounds, so what is left of it is
      // shorter by the same amount. Every other input is read from its own start and keeps its own
      // bound.
      val windowed = index == 0 && seekBase != null
      val length =
        input.durationSeconds?.let { if (windowed) it - at.toDouble(DurationUnit.SECONDS) else it }
      length?.let {
        add("-t")
        add(formatSeconds(it))
      }
      add("-i")
      add(resolvedInputs[index])
    }

    if (copy) {
      // A transmux writes the source's own stream, so the frames the preview shows are the source's
      // own too. -s is a no-op wherever the coded frame already matches, and pins the pump's frame
      // size where a container's display aspect makes it differ.
      add("-map")
      add("0:v:0")
      add("-s")
      add("${output.size.width}x${output.size.height}")
    } else {
      add("-filter_complex")
      add(resolvedGraph(resolvedSidecars))

      // ffmpeg refuses a graph with an output nothing reads, so the audio branch this backend does
      // not monitor is still mapped, into a muxer that writes nothing.
      audioLabel?.let {
        add("-map")
        add("[$it]")
        add("-f")
        add("null")
        add("-")
      }

      add("-map")
      add("[${checkNotNull(videoLabel) { NO_VIDEO_TO_PREVIEW }}]")
    }

    add("-an")
    add("-f")
    add("rawvideo")
    add("-pix_fmt")
    add(PREVIEW_PIXEL_FORMAT)
    // -vsync 0 under its old name, which ffmpeg 9 no longer takes. Frames reach the pipe as the
    // graph emitted them, neither duplicated up to a rate nor dropped down to one.
    add("-fps_mode")
    add("passthrough")

    config.threads?.let {
      add("-threads")
      add(it.toString())
    }

    add("pipe:1")
  }

/**
 * How many bytes one frame of this invocation's output occupies on the pipe.
 */
internal val Invocation.previewFrameBytes: Int
  get() = output.size.width * output.size.height * PREVIEW_CHANNELS

// Seconds, or null where there is nothing to move. A seek to zero costs a seek that lands where the
// demuxer already is, and an offset of zero moves no timestamp anywhere.
private fun Duration.positiveSeconds(): Double? = toDouble(DurationUnit.SECONDS).takeIf { it > 0.0 }

/**
 * The first output frame at or after this time, as the seconds that frame opens on.
 *
 * [OutputFormat.frameRate] is the grid the whole plan is sampled onto, the sendcmd sidecar's
 * intervals included, and an input seek delivers the first frame at or after what it was asked for.
 * A branch moved to the raw scrub would therefore be read one frame behind the one the clip hands
 * the merge whenever the scrub falls inside a frame period.
 *
 * The arithmetic is integer so that a scrub already on the grid is not carried onto the frame after
 * it, and the answer divides the same way the sampler writes a frame's own time.
 */
private fun Duration.onFrameGrid(frameRate: Int?): Double {
  val rate = frameRate?.takeIf { it > 0 } ?: return toDouble(DurationUnit.SECONDS)
  val frames = (inWholeNanoseconds * rate + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND

  return frames.toDouble() / rate
}

private const val NANOS_PER_SECOND = 1_000_000_000L

private const val NO_VIDEO_TO_PREVIEW =
  "The plan writes no video track, so there is nothing for a preview to pump."
