package dev.jordond.filmstrip.ffmpeg.internal.probe

import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain

/**
 * The ffprobe call that decodes the first packets of [path]'s first video stream and prints how many frames came out.
 *
 * A few packets are read rather than one, so a decoder that holds its first frame back until it sees more input still
 * hands one over.
 */
internal fun firstFrameArguments(
  toolchain: Toolchain,
  path: String,
): List<String> =
  listOf(
    toolchain.ffprobe,
    "-v",
    "warning",
    "-select_streams",
    "v:0",
    "-read_intervals",
    "%+#$FIRST_FRAME_PACKETS",
    "-count_frames",
    "-show_entries",
    "stream=nb_read_frames",
    "-of",
    "default=nw=1:nk=1",
    path,
  )

/**
 * What the [stdout] and [stderr] of [firstFrameArguments] say about the first frame.
 *
 * A stream ffprobe could not decode prints zero frames or nothing at all. A stream this build has no decoder for prints
 * zero too, but its warning names the missing decoder.
 */
internal fun parseFirstFrame(
  stdout: String,
  stderr: String,
): FirstFrame =
  when {
    NO_DECODER in stderr -> FirstFrame.NoDecoder
    stdout.lineSequence().mapNotNull { it.trim().toIntOrNull() }.any { it > 0 } -> FirstFrame.Decodes
    else -> FirstFrame.Damaged
  }

/**
 * What decoding the first frame of a source's video stream found: a frame, a stream that gave none, or a codec this
 * ffmpeg build has no decoder for, which says nothing about the bytes.
 */
internal enum class FirstFrame { Decodes, Damaged, NoDecoder }

private const val NO_DECODER = "Unsupported codec with id"
private const val FIRST_FRAME_PACKETS = 4
