package dev.jordond.filmstrip.ffmpeg.internal.probe

import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.transform.internal.copy.COPY_PACKET_MARGIN
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.MAX_COPY_PACKET_RATE
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The ffprobe call that lists [path]'s video packets in decode order from [from] to [to] and [COPY_PACKET_MARGIN].
 *
 * Packets rather than frames, so nothing is decoded. ffprobe opens the read on the sync sample at or before [from].
 */
internal fun copyPacketArguments(
  toolchain: Toolchain,
  path: String,
  from: Duration,
  to: Duration,
): List<String> =
  listOf(
    toolchain.ffprobe,
    "-v",
    "error",
    "-select_streams",
    "v:0",
    "-show_entries",
    "packet=pts_time,flags",
    "-of",
    "csv=p=0",
    "-read_intervals",
    "${from.printedSeconds}%${(to + COPY_PACKET_MARGIN).printedSeconds}",
    path,
  )

/**
 * The packet one line of [copyPacketArguments]'s output lists, or null for a blank line.
 *
 * ffprobe prints the packets in decode order, one a line. A packet the container gave no time keeps its place with an
 * infinite one, since dropping it would move every packet after it in the count.
 */
internal fun parseCopyPacket(line: String): CopyPacket? {
  if (line.isBlank()) return null
  val fields = line.split(',')
  return CopyPacket(
    pts = fields[0].trim().toDoubleOrNull()?.seconds ?: Duration.INFINITE,
    key = fields.getOrNull(1)?.startsWith(KEY_FLAG) == true,
  )
}

/**
 * Why a copy whose read listed more than [cap] packets over the window from [from] to [to] is refused.
 */
internal fun packetCapReason(
  cap: Int,
  from: Duration,
  to: Duration,
): String =
  "ffprobe listed more than $cap video packets between ${from.printedSeconds} s and " +
    "${(to + COPY_PACKET_MARGIN).printedSeconds} s, past the $MAX_COPY_PACKET_RATE fps a copy reads its packets at."

/**
 * Why a copy whose packets ffprobe could not list is refused.
 */
internal const val UNLISTED_PACKETS: String =
  "ffprobe could not list the source's video packets over the copy's window, and a copy that ends before its " +
    "source does counts the video it keeps by them."

private const val KEY_FLAG = 'K'

private val Duration.printedSeconds: String get() = formatSeconds(toDouble(DurationUnit.SECONDS))
