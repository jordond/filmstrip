package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.ExportError

// ffmpeg returns an AVERROR truncated to a byte, so the codes above 128 recover by subtracting 256.
private const val INVALID_ARGUMENT = 234 // AVERROR(EINVAL)
private const val NO_SUCH_FILE = 254 // AVERROR(ENOENT)
private const val BAD_OPTION = 8
private const val SIGNAL_FLOOR = 128
private const val BYTE_RANGE = 256

/**
 * Classifies a failed run.
 *
 * The exit code is the coarse answer and stderr is the operational one: running out of disk and
 * being refused the output path both come back as generic failures otherwise, and those are the two
 * a caller can actually act on.
 *
 * @param stderr The tail of what the child wrote, which becomes the error's message.
 * @param sink Where the output was going, for a failure that is about the destination.
 */
internal fun classifyExit(
  exitCode: Int,
  stderr: String,
  sink: String,
): ExportError {
  val message = stderr.ifBlank { "ffmpeg exited with $exitCode and wrote nothing to stderr." }

  if (stderr.contains("No space left on device")) {
    return ExportError.InsufficientStorage(requiredBytes = null, message = message)
  }
  if (stderr.contains("Permission denied")) {
    return ExportError.SinkUnwritable(sink = sink, message = message)
  }

  return when {
    exitCode == SPAWN_FAILED -> ExportError.Underlying(exitCode, message)
    exitCode == BAD_OPTION -> ExportError.Underlying(exitCode, message)
    exitCode == INVALID_ARGUMENT -> ExportError.InvalidComposition(message)
    exitCode == NO_SUCH_FILE -> ExportError.SourceUnreadable(source = sink, message = message)
    exitCode > SIGNAL_FLOOR -> ExportError.Underlying(exitCode - BYTE_RANGE, message)
    else -> ExportError.Underlying(exitCode, message)
  }
}

/**
 * Keeps the last [limit] lines of a stream, so a message stays loggable however long the run was.
 *
 * `ExportError.message` is documented as safe to log and unsuitable for parsing, which is what a
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
