package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.FilmstripError
import kotlinx.io.IOException

// ffmpeg returns an AVERROR truncated to a byte, so the codes above 128 recover by subtracting 256.
private const val INVALID_ARGUMENT = 234 // AVERROR(EINVAL)
private const val BAD_OPTION = 8
private const val SIGNAL_FLOOR = 128
private const val BYTE_RANGE = 256

// What a full disk reads as: strerror(ENOSPC) on Linux and macOS, and the Win32 message on Windows.
private val DISK_FULL = listOf("No space left on device", "There is not enough space on the disk")

// strerror(EACCES) on Linux, macOS and Windows alike.
private const val PERMISSION_DENIED = "Permission denied"

/**
 * Classifies a failed run.
 *
 * stderr is read first, for the two failures it names in words: running out of disk and being refused a path. A
 * refusal is pinned on the input or the output whose path ffmpeg named with it, and one that names neither falls
 * through. What the engine found on disk after the run comes next, because a missing file comes back as
 * AVERROR(ENOENT) whichever side was missing. The exit code is the fallback, and in every case stderr is only the
 * message.
 *
 * @param stderr The tail of what the child wrote, which becomes the error's message.
 * @param sink Where the output was going, for a failure that is about the destination.
 * @param inputs Every input the command line named, for a refusal that is about one of them.
 * @param missingSource The source an input that was no longer on disk after the run was reported by, or null when
 * every input was still there.
 * @param sinkParentMissing Whether the output's parent directory was missing after the run.
 */
internal fun classifyExit(
  exitCode: Int,
  stderr: String,
  sink: String,
  inputs: List<ResolvedInput> = emptyList(),
  missingSource: String? = null,
  sinkParentMissing: Boolean = false,
): FilmstripError {
  val message = stderr.ifBlank { "ffmpeg exited with $exitCode and wrote nothing to stderr." }
  val refused = stderr.contains(PERMISSION_DENIED)
  // ffmpeg opens every input before its output, so a refused input is what stopped a run that names both.
  val refusedSource = if (refused) inputs.firstOrNull { stderr.refuses(it.path, "input") }?.source else null

  return when {
    isDiskFull(stderr) -> FilmstripError.InsufficientStorage(requiredBytes = null, message = message)
    refusedSource != null -> FilmstripError.SourceUnreadable(source = refusedSource, message = message)
    refused && stderr.refuses(sink, "output") -> FilmstripError.SinkUnwritable(sink = sink, message = message)
    missingSource != null -> FilmstripError.SourceUnreadable(source = missingSource, message = message)
    sinkParentMissing -> FilmstripError.SinkUnwritable(sink = sink, message = message)
    exitCode == SPAWN_FAILED -> FilmstripError.Underlying(exitCode, message)
    exitCode == BAD_OPTION -> FilmstripError.Underlying(exitCode, message)
    exitCode == INVALID_ARGUMENT -> FilmstripError.InvalidComposition(message)
    exitCode > SIGNAL_FLOOR -> FilmstripError.Underlying(exitCode - BYTE_RANGE, message)
    else -> FilmstripError.Underlying(exitCode, message)
  }
}

/**
 * Whether this stderr names [path] as the [side] ffmpeg was refused.
 *
 * An output's refusal is a line of the path followed by the reason, with a prefix in front of it. An input's reason
 * goes on a line of its own and the path on the `Error opening input file` line that follows it. Whole lines are
 * matched, so a path that ends another path is not taken for it.
 */
private fun String.refuses(
  path: String,
  side: String,
): Boolean {
  val named = "$path: $PERMISSION_DENIED"
  return lineSequence().any { it.endsWith(" $named") || it == "Error opening $side file $path." }
}

/**
 * Whether [text] is the operating system saying the disk is full, whether ffmpeg or this process wrote it.
 */
internal fun isDiskFull(text: String): Boolean = DISK_FULL.any { text.contains(it) }

/**
 * Keeps the last [limit] lines of a stream, so a message stays loggable however long the run was.
 *
 * `FilmstripError.message` is documented as safe to log and unsuitable for parsing, which is what a
 * bounded tail of stderr is.
 */
internal class StderrTail(
  private val limit: Int = 64,
) {
  private val lines = ArrayDeque<String>()

  fun accept(line: String) {
    if (line.isBlank()) return
    lines.addLast(line)
    if (lines.size > limit) lines.removeFirst()
  }

  fun text(): String = lines.joinToString("\n")
}

/**
 * What a failure to write the scratch directory is reported as.
 *
 * A full disk is [FilmstripError.InsufficientStorage], read off the same words a full disk is read off ffmpeg's stderr
 * with. Anything else is an [FilmstripError.Underlying] with no platform code, since the file system gave none.
 */
internal fun scratchFailure(failure: IOException): FilmstripError {
  val message = failure.message ?: SCRATCH_UNWRITABLE
  return if (isDiskFull(message)) {
    FilmstripError.InsufficientStorage(requiredBytes = null, message = message)
  } else {
    FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, message)
  }
}

/**
 * What a preview pump whose ffmpeg never started is reported as.
 *
 * An [FilmstripError.Underlying] with no platform code, whose message carries the operating system's reason after
 * filmstrip's own sentence when there is one.
 */
internal fun unspawnablePump(failure: IOException): FilmstripError {
  val reason = failure.message?.trim()?.takeIf { it.isNotEmpty() }
  val message = if (reason == null) PUMP_UNSPAWNABLE else "$PUMP_UNSPAWNABLE $reason"
  return FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, message)
}

internal const val SOURCE_GONE: String = "The source could not be found when the export started."

internal const val PUMP_UNSPAWNABLE: String = "The ffmpeg preview process could not be started."

private const val SCRATCH_UNWRITABLE = "The export's scratch directory could not be written."
