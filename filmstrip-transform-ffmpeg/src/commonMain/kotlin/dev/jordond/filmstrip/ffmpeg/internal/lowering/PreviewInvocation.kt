package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
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
 * A sibling of [arguments]. The graph, its labels and its inputs are the
 * ones [GraphLowering] already wrote, so a previewed frame and an exported one come out of one
 * filter chain. What differs is the window, `-ss` ahead of the first input, and the sink, raw RGBA
 * frames on stdout rather than a container on disk.
 *
 * `-copyts -start_at_zero` hold the source's own timestamps in front of the graph. Without them
 * ffmpeg rebases a seeked input to zero and every trim the graph carries shifts by the seek. With
 * no seek they leave the timestamps exactly where an export sees them.
 *
 * The clip carries the seek, to the [previewSeek] of the scrub. An overlay image seeked into
 * delivers nothing at all, so a branch [InputSpec.rebased] marks is carried to the same point by
 * `-itsoffset` instead: its timestamps move to the composition times the clip opens on, and the
 * `setpts` that branch ends on takes the move back off before the merge lines the frames up.
 *
 * A file keeping an SD colour displays it through its tags, and RGBA frames carry none, so the pump moves that colour
 * onto BT.709's last thing, which is what the file displays as. See [Invocation.previewColor].
 *
 * [FfmpegConfig.extraArgs] does not reach here. It is documented as going to every encode, and this
 * runs none.
 *
 * @param at Where in the composition the first frame comes from, which the pump opens on the output
 *   slot it lands on. Ignored when [Invocation.seekBase] is null, since that timeline cannot be
 *   windowed by an input seek and is read forward instead.
 * @param frames How the frames for [at] split between the graph and the pump. Defaults to working
 *   it out from [at], for a caller with nothing else to feed it. A caller that also opens the pump's
 *   stream should pass the one [previewFrames] gave it, so the split is worked out once for both.
 */
internal fun Invocation.previewArguments(
  toolchain: Toolchain,
  config: FfmpegConfig,
  resolvedInputs: List<String>,
  resolvedSidecars: List<String>,
  at: Duration,
  frames: PreviewFrames = previewFrames(at),
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

    addAll(previewInputArguments(resolvedInputs, at))
    addAll(previewMapArguments(resolvedSidecars, previewSeek(at)))

    // The graph cuts each clip to its slots counted from the first frame it is handed, and a
    // windowed preview hands it the frame on the landing's slot, so that cut falls past the end of
    // the video. A copy has no graph to cut it, and its input bound takes in the frame on the slot
    // its end rounds down past. The graph is stopped on the frames [frames] says it renders instead.
    frames.rendered?.let { rendered ->
      add("-frames:v")
      add(rendered.toString())
    }

    addAll(previewSinkArguments(config))
  }

// Every input, the clip seeked to where the preview opens and each rebased branch moved there instead.
private fun Invocation.previewInputArguments(
  resolvedInputs: List<String>,
  at: Duration,
): List<String> =
  buildList {
    val opening = previewSeek(at)
    val seek = seekBase?.plus(opening)?.positiveSeconds()
    // What a rebased branch has to be moved by to land where the seek does. The clip's branch opens
    // with setpts=PTS-STARTPTS, so its clock reads zero at the seek whatever the seek was, and a
    // branch left at its own start would be read against that zero from the top.
    val branchOffset = opening.positiveSeconds()
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
      addAll(input.orientationArguments())
      addAll(input.decoderArguments())
      addAll(input.liveArguments())
      // The seek has already eaten its own point out of the window the input bounds, so what is left
      // of it is shorter by the same amount. Every other input is read from its own start and keeps
      // its own bound.
      val windowed = index == 0 && seekBase != null
      val length =
        input.durationSeconds?.let { if (windowed) it - opening.toDouble(DurationUnit.SECONDS) else it }
      length?.let {
        add("-t")
        add(formatSeconds(it))
      }
      add("-i")
      add(resolvedInputs[index])
    }
  }

// The source's own stream for a copy, and the graph's video pad for everything else.
private fun Invocation.previewMapArguments(
  resolvedSidecars: List<String>,
  windowOpensAt: Duration,
): List<String> =
  buildList {
    if (copy) {
      // A copy writes the source's own stream, so the frames the preview shows are the source's
      // own too. -s is a no-op wherever the coded frame already matches, and pins the pump's frame
      // size where a container's display aspect makes it differ.
      add("-map")
      add("0:v:0")
      if (previewColor.isNotEmpty()) {
        add("-vf")
        add(previewColor.render())
      }
      add("-s")
      add("${output.size.width}x${output.size.height}")
    } else {
      val out = checkNotNull(videoLabel) { NO_VIDEO_TO_PREVIEW }
      val shown = if (previewColor.isEmpty()) out else PREVIEW_SHOWN
      add(GRAPH_FLAG)
      add(
        resolvedGraph(resolvedSidecars, windowOpensAt) +
          if (previewColor.isEmpty()) "" else ";[$out]${previewColor.render()}[$shown]",
      )

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
      add("[$shown]")
    }
  }

// Raw RGBA frames on stdout, handed over as the graph emitted them.
private fun previewSinkArguments(config: FfmpegConfig): List<String> =
  buildList {
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

private const val PREVIEW_SHOWN = "vshown"

private const val NO_VIDEO_TO_PREVIEW =
  "The plan writes no video track, so there is nothing for a preview to pump."
