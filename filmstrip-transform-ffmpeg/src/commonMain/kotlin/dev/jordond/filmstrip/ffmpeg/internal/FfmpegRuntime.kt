package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.media.MediaInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The binaries, and what has already been asked of them.
 *
 * One per [dev.jordond.filmstrip.ffmpeg.ffmpegBackend] call, shared by the export engine and the
 * prober. Sharing is the point: resolving the toolchain costs three spawns for the version banner,
 * the filter list and the encoder list, and probing a source costs another.
 */
internal class FfmpegRuntime(
  val config: FfmpegConfig,
) {
  private val runner = ProcessRunner()
  private val locator = ToolchainLocator(config, runner)
  private val probeLock = Mutex()
  private val probes = mutableMapOf<String, MediaInfo?>()

  suspend fun toolchain(): ToolchainResult = locator.toolchain()

  suspend fun capture(command: List<String>): ProcessOutput = runner.capture(command)

  suspend fun stream(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int = runner.run(command, onStdout, onStderr)

  /**
   * Reads [path] through ffprobe, or null when it could not be read.
   *
   * Cached by path, negatives included, so a plan that names the same clip in several places spawns
   * one ffprobe and a re-lowering spawns none.
   */
  suspend fun probe(
    toolchain: Toolchain,
    path: String,
  ): MediaInfo? =
    probeLock.withLock {
      if (probes.containsKey(path)) return@withLock probes[path]
      read(toolchain, path).also { probes[path] = it }
    }

  /**
   * Reads [path] again, replacing whatever was cached for it.
   *
   * For a file this process has just written. The cache is keyed by path, and a path written twice
   * is two different files.
   */
  suspend fun reprobe(
    toolchain: Toolchain,
    path: String,
  ): MediaInfo? =
    probeLock.withLock {
      read(toolchain, path).also { probes[path] = it }
    }

  private suspend fun read(
    toolchain: Toolchain,
    path: String,
  ): MediaInfo? {
    val output = capture(listOf(toolchain.ffprobe) + PROBE_ARGUMENTS + path)
    return if (output.started && output.exitCode == 0) parseMediaInfo(output.stdout) else null
  }

  /**
   * Resolves the toolchain and then reads [path], for a caller that holds no [Toolchain] of its own.
   */
  suspend fun probeSource(path: String): ProbeOutcome =
    when (val result = toolchain()) {
      is ToolchainResult.Unavailable -> {
        ProbeOutcome.NoToolchain(result.error)
      }
      is ToolchainResult.Available -> {
        val info = probe(result.toolchain, path)
        if (info == null) ProbeOutcome.Unreadable else ProbeOutcome.Read(info)
      }
    }
}

/**
 * What [FfmpegRuntime.probe] found, separating "there is nothing to run" from "it ran and could not
 * read the file", which are different failures with different fixes.
 */
internal sealed interface ProbeOutcome {
  class Read(
    val info: MediaInfo,
  ) : ProbeOutcome

  class NoToolchain(
    val error: ExportError.ToolchainMissing,
  ) : ProbeOutcome

  data object Unreadable : ProbeOutcome
}
