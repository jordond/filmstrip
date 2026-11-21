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
 * Whether the [stdout] and [stderr] of [firstFrameArguments] say the first frame failed to decode.
 *
 * A stream ffprobe could not decode prints zero frames or nothing at all. A stream this build has no decoder for prints
 * zero too, but its warning names the missing decoder, and that reads as whole since it says nothing about the bytes.
 */
internal fun parseFirstFrameDamaged(
  stdout: String,
  stderr: String,
): Boolean {
  if (NO_DECODER in stderr) return false
  return stdout.lineSequence().mapNotNull { it.trim().toIntOrNull() }.none { it > 0 }
}

private const val NO_DECODER = "Unsupported codec with id"
private const val FIRST_FRAME_PACKETS = 4
