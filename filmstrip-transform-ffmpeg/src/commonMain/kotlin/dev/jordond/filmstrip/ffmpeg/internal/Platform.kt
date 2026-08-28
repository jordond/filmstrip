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

// Reads one environment variable, or null when it is unset or empty.
internal expect fun environmentVariable(name: String): String?

// What separates entries in PATH, and what suffixes an executable may carry. Both are properties of
// the host rather than of the target.
internal expect fun pathEntries(): List<String>

internal expect fun executableSuffixes(): List<String>

// Resolves to an absolute path. Spawning a relative name searches the current directory first on
// Windows, which is a way to run the wrong binary.
internal expect fun absolutePathOf(path: String): String
