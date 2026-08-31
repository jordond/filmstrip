package dev.jordond.filmstrip.ffmpeg.internal

// Everything in this module that is not a pure function of its inputs lives behind this file.
// Adding linuxX64 or mingwX64 means writing one more actual of it, not a second backend: the
// version gate, the graph builder, the progress parser and the exit-code mapping are all common.

// Spawns a child process and drives its pipes.
internal expect class ProcessRunner() {
  /**
   * Runs [command] to completion and returns what it wrote.
   *
   * For the short calls: the version banner, the filter list, an ffprobe.
   */
  suspend fun capture(command: List<String>): ProcessOutput

  /**
   * Runs [command], reporting each line as it arrives.
   *
   * Cancelling the calling coroutine stops the child by writing `q` to its stdin, which is how
   * ffmpeg is asked to finalise what it has written rather than being killed part way through a
   * container. A child that ignores it is terminated after [STOP_GRACE_MILLIS].
   *
   * @return The child's exit code.
   */
  suspend fun run(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int

  /**
   * Spawns [command] and hands back a handle that reads its stdout as whole frames.
   *
   * @return The stream, or null when the child never started.
   */
  suspend fun frames(command: List<String>): FrameStream?
}

/**
 * A running child whose stdout is read as fixed-size binary frames rather than as lines.
 *
 * The line reader behind [ProcessRunner.run] is right for `-progress` output and wrong for pixels:
 * a frame is a fixed number of bytes carrying no delimiter, and any byte inside one can be a
 * newline.
 */
internal interface FrameStream {
  /**
   * The child's process id, for a caller that has to prove it is gone.
   */
  val processId: Long?

  /**
   * Whether the child is still running.
   */
  val isAlive: Boolean

  /**
   * Fills [frame] with the next frame's bytes.
   *
   * @return false once the stream ends, including when it ends part way through a frame.
   */
  suspend fun read(frame: ByteArray): Boolean

  /**
   * The tail of what the child wrote to stderr.
   */
  fun errors(): String

  /**
   * Stops the child and closes its pipes. Idempotent, and never returns with the child running.
   */
  fun close()
}

/**
 * What a finished process wrote, and how it ended.
 *
 * @property exitCode The child's exit code, or [SPAWN_FAILED] when it never started.
 */
internal class ProcessOutput(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
) {
  val started: Boolean get() = exitCode != SPAWN_FAILED
}

// A process that never started has no exit code of its own, and every real code is a byte.
internal const val SPAWN_FAILED: Int = -1

internal const val STOP_GRACE_MILLIS: Long = 5_000

// A preview writes no container, so there is no moov atom to wait for and nothing to lose by
// killing the child outright. Short enough that a seek respawn does not stall on it.
internal const val FRAME_STOP_GRACE_MILLIS: Long = 250

/**
 * Runs [block] with every other caller of it held out.
 *
 * For the runtime cache, which is read from ordinary constructors and so cannot take the
 * suspending mutex the rest of this module locks with.
 */
internal expect fun <T> exclusively(block: () -> T): T

// Reads one environment variable, or null when it is unset or empty.
internal expect fun environmentVariable(name: String): String?

// What separates entries in PATH, and what suffixes an executable may carry. Both are properties of
// the host rather than of the target.
internal expect fun pathEntries(): List<String>

internal expect fun executableSuffixes(): List<String>

// Resolves to an absolute path. Spawning a relative name searches the current directory first on
// Windows, which is a way to run the wrong binary.
internal expect fun absolutePathOf(path: String): String
