package dev.jordond.filmstrip.ffmpeg.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How far back of [syncSampleArguments]'s cut ffprobe is asked to read.
 *
 * ffprobe seeks backwards to the sync sample at or before the interval opens and reports it, so a
 * window shorter than the gap between two sync samples still names the one the cut sits in. The
 * bound is on what the probe reads, never on the answer it gives.
 */
internal val SYNC_SAMPLE_WINDOW: Duration = 10.seconds

/**
 * The ffprobe call that lists [path]'s sync samples around [at].
 *
 * `-skip_frame nokey` leaves the decoder handing back key frames alone, and `-read_intervals` holds
 * the read to [SYNC_SAMPLE_WINDOW] ahead of the cut rather than walking the whole file.
 */
internal fun syncSampleArguments(
  toolchain: Toolchain,
  path: String,
  at: Duration,
): List<String> {
  val from = (at - SYNC_SAMPLE_WINDOW).coerceAtLeast(Duration.ZERO)
  return listOf(
    toolchain.ffprobe,
    "-v",
    "error",
    "-select_streams",
    "v:0",
    "-skip_frame",
    "nokey",
    "-show_entries",
    "frame=pts_time",
    "-of",
    "csv=p=0",
    "-read_intervals",
    "${from.printedSeconds}%${at.printedSeconds}",
    path,
  )
}

/**
 * The latest sync sample [output] names at or before [at], or null when it names none.
 *
 * Null covers a source with no video stream, which lists nothing at all, as well as a cut ffprobe
 * found no sync sample ahead of.
 */
internal fun parseSyncSample(
  output: String,
  at: Duration,
): Duration? =
  output
    .lineSequence()
    .mapNotNull { it.substringBefore(',').trim().toDoubleOrNull() }
    .map { it.seconds }
    .filter { it <= at }
    .maxOrNull()

private val Duration.printedSeconds: String get() = formatSeconds(toDouble(DurationUnit.SECONDS))
