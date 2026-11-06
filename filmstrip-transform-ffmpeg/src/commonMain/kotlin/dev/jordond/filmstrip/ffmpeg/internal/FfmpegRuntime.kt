package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime.Companion.of
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SdColor
import dev.jordond.filmstrip.ffmpeg.internal.probe.PROBE_ARGUMENTS
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseMediaInfo
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseSdColor
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseSyncSample
import dev.jordond.filmstrip.ffmpeg.internal.probe.syncSampleArguments
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.DEFAULT_BYTES_ROOT
import dev.jordond.filmstrip.ffmpeg.internal.process.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessOutput
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.internal.process.exclusively
import dev.jordond.filmstrip.media.MediaInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

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
  val bytesRoot: BytesRoot = DEFAULT_BYTES_ROOT,
) {
  private val runner = ProcessRunner()
  private val locator = ToolchainLocator(config, runner)
  private val probeLock = Mutex()
  private val probes = mutableMapOf<String, MediaInfo>()

  // Written beside probes by the one read that fills it, and held under the same lock.
  private val sdColors = mutableMapOf<String, SdColor?>()
  private val syncSampleLock = Mutex()
  private val syncSamples = mutableMapOf<Pair<String, Duration>, Duration?>()
  private val capabilityLock = Mutex()
  private var capabilities: DeviceCapabilities? = null

  // How many times ffprobe was actually spawned. The cache is what holds this to one per path
  // however often the same edit is lowered.
  var probeSpawns: Int = 0
    private set

  // How many times the SD colour read spawned ffprobe on its own, counted the same way.
  var sdColorSpawns: Int = 0
    private set

  // How many times the sync-sample probe was actually spawned, counted the same way.
  var syncSampleSpawns: Int = 0
    private set

  // How many times the encoder ladder was actually measured, counted the same way.
  var capabilityMeasures: Int = 0
    private set

  // How many times an export's ffmpeg was spawned, so an export refused up front can be shown to
  // have started nothing.
  var streamSpawns: Int = 0
    private set

  suspend fun toolchain(): ToolchainResult = locator.toolchain()

  /**
   * What this toolchain can encode, measured by [measure] the first time it is asked for.
   *
   * Held on the runtime, so every engine a process builds for its components shares one measurement
   * of the ladder. The lock is held across the measurement, so two callers arriving together measure
   * once between them.
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
  ): Int {
    streamSpawns++
    return runner.run(command, onStdout, onStderr)
  }

  /**
   * Spawns [command] and reads its stdout as whole frames, for the preview pump.
   *
   * @throws kotlinx.io.IOException When the child never started.
   */
  suspend fun frames(command: List<String>): FrameStream = runner.frames(command)

  /**
   * Reads [path] through ffprobe, or null when it could not be read.
   *
   * Each file read is cached by path, so a plan that names the same clip in several places spawns one ffprobe and a
   * re-lowering spawns none. A path that could not be read is not held, so the next caller asks again and finds a
   * file that turned up since.
   */
  suspend fun probe(
    toolchain: Toolchain,
    path: String,
  ): MediaInfo? =
    probeLock.withLock {
      probes[path] ?: read(toolchain, path)?.also { probes[path] = it }
    }

  /**
   * The [SdColor] of [path]'s video stream, or null when it has none or [path] could not be read.
   *
   * A file [probe] read already answers from that spawn. One a registered prober answered for spawns once for this,
   * since a [MediaInfo] holds no primaries, and is cached the same way.
   */
  suspend fun sdColor(
    toolchain: Toolchain,
    path: String,
  ): SdColor? =
    probeLock.withLock {
      if (!sdColors.containsKey(path)) {
        sdColorSpawns++
        val output = capture(listOf(toolchain.ffprobe) + PROBE_ARGUMENTS + path)
        if (output.started && output.exitCode == 0) sdColors[path] = parseSdColor(output.stdout)
      }
      sdColors[path]
    }

  /**
   * The sync sample at or before [at] in [path]'s video stream, or null when there is none.
   *
   * Cached by path and cut, negatives included, so the plan, the resolve behind a preview and the
   * export of one edit read the same answer.
   */
  suspend fun syncSampleAtOrBefore(
    toolchain: Toolchain,
    path: String,
    at: Duration,
  ): Duration? =
    syncSampleLock.withLock {
      val key = path to at
      if (syncSamples.containsKey(key)) return@withLock syncSamples[key]
      readSyncSample(toolchain, path, at).also { syncSamples[key] = it }
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
      read(toolchain, path).also {
        if (it == null) {
          probes.remove(path)
          sdColors.remove(path)
        } else {
          probes[path] = it
        }
      }
    }

  private suspend fun read(
    toolchain: Toolchain,
    path: String,
  ): MediaInfo? {
    probeSpawns++
    val output = capture(listOf(toolchain.ffprobe) + PROBE_ARGUMENTS + path)
    if (!output.started || output.exitCode != 0) return null
    sdColors[path] = parseSdColor(output.stdout)
    return parseMediaInfo(output.stdout)
  }

  private suspend fun readSyncSample(
    toolchain: Toolchain,
    path: String,
    at: Duration,
  ): Duration? {
    syncSampleSpawns++
    val output = capture(syncSampleArguments(toolchain, path, at))
    return if (output.started && output.exitCode == 0) parseSyncSample(output.stdout, at) else null
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
    // configuration reach the same runtime. Held in use order and capped: a process usually
    // registers one configuration, and a caller that keeps spelling fresh ones drops the least
    // recently used. An evicted runtime owns no process and no handle, only the toolchain and
    // probe answers it had cached, so the next caller to name that configuration pays for those
    // again and nothing else.
    const val MAX_RUNTIMES = 8

    private val runtimes = LinkedHashMap<FfmpegConfig, FfmpegRuntime>()

    /**
     * The runtime [config] resolves through, built the first time it is asked for.
     *
     * Every backend component goes through this, so an export, a probe and a preview built from
     * the same configuration resolve the binaries once between them and share one probe cache.
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
    fun unshared(
      config: FfmpegConfig,
      bytesRoot: BytesRoot = DEFAULT_BYTES_ROOT,
    ): FfmpegRuntime = FfmpegRuntime(config, bytesRoot)
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
    val error: FilmstripError.ToolchainMissing,
  ) : ProbeOutcome

  data object Unreadable : ProbeOutcome
}
