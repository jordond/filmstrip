package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.media.MediaInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The binaries, and what has already been asked of them.
 *
 * One per distinct [FfmpegConfig], shared by the export engine, the prober and the preview pump.
 * Sharing is the point: resolving the toolchain costs three spawns for the version banner, the
 * filter list and the encoder list, probing a source costs another, and measuring the encoder
 * ladder costs one encode per encoder the build carries. Build one through [of].
 */
internal class FfmpegRuntime private constructor(
  val config: FfmpegConfig,
) {
  private val runner = ProcessRunner()
  private val locator = ToolchainLocator(config, runner)
  private val probeLock = Mutex()
  private val probes = mutableMapOf<String, MediaInfo?>()
  private val capabilityLock = Mutex()
  private var capabilities: DeviceCapabilities? = null

  // How many times ffprobe was actually spawned. The cache is what holds this to one per path
  // however often the same edit is lowered.
  var probeSpawns: Int = 0
    private set

  // How many times the encoder ladder was actually measured, counted the same way.
  var capabilityMeasures: Int = 0
    private set

  suspend fun toolchain(): ToolchainResult = locator.toolchain()

  /**
   * What this toolchain can encode, measured by [measure] the first time it is asked for.
   *
   * Held here rather than on an engine, because a process builds an engine for every component that
   * wants one and each would otherwise pay for the whole ladder. The lock is held across the
   * measurement rather than around the assignment, so two callers arriving together measure once
   * between them instead of once each.
   *
   * @param measure Runs the ladder against the toolchain this runtime resolved.
   */
  suspend fun capabilities(measure: suspend () -> DeviceCapabilities): DeviceCapabilities =
    capabilityLock.withLock {
      capabilities ?: measure().also {
        capabilities = it
        capabilityMeasures++
      }
    }

  suspend fun capture(command: List<String>): ProcessOutput = runner.capture(command)

  suspend fun stream(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int = runner.run(command, onStdout, onStderr)

  /**
   * Spawns [command] and reads its stdout as whole frames, for the preview pump.
   */
  suspend fun frames(command: List<String>): FrameStream? = runner.frames(command)

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
    probeSpawns++
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

  companion object {
    // Keyed on the config, which compares structurally, so two callers that spelled the same
    // configuration reach the same runtime. Held in use order and capped: a process registers one
    // configuration in every case that is not a test, and a caller that keeps spelling fresh ones
    // drops the least recently used rather than growing this forever. An evicted runtime owns no
    // process and no handle, only the toolchain and probe answers it had cached, so the next
    // caller to name that configuration pays for those again and nothing else.
    const val MAX_RUNTIMES = 8

    private val runtimes = LinkedHashMap<FfmpegConfig, FfmpegRuntime>()

    /**
     * The runtime [config] resolves through, built the first time it is asked for.
     *
     * Every backend component goes through this, so an export, a probe and a preview built from
     * the same configuration resolve the binaries once between them and share one probe cache
     * rather than answering the same questions three times.
     */
    fun of(config: FfmpegConfig): FfmpegRuntime =
      exclusively {
        runtimes.remove(config)?.also { runtimes[config] = it }
          ?: FfmpegRuntime(config).also {
            runtimes[config] = it
            while (runtimes.size > MAX_RUNTIMES) runtimes.remove(runtimes.keys.first())
          }
      }

    /**
     * A runtime nothing else holds, for a caller measuring what a cold cache costs.
     */
    fun unshared(config: FfmpegConfig): FfmpegRuntime = FfmpegRuntime(config)
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
