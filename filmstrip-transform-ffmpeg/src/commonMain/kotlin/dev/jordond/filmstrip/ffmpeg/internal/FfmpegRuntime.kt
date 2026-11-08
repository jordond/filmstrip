package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime.Companion.of
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SdColor
import dev.jordond.filmstrip.ffmpeg.internal.probe.PROBE_ARGUMENTS
import dev.jordond.filmstrip.ffmpeg.internal.probe.SYNC_SAMPLE_WINDOW
import dev.jordond.filmstrip.ffmpeg.internal.probe.UNLISTED_PACKETS
import dev.jordond.filmstrip.ffmpeg.internal.probe.copyPacketArguments
import dev.jordond.filmstrip.ffmpeg.internal.probe.packetCapReason
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseCopyPacket
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseMediaInfo
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseSdColor
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseSyncSample
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseVideoMatrix
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseVideoPixelFormat
import dev.jordond.filmstrip.ffmpeg.internal.probe.syncSampleArguments
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.DEFAULT_BYTES_ROOT
import dev.jordond.filmstrip.ffmpeg.internal.process.FileStamp
import dev.jordond.filmstrip.ffmpeg.internal.process.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveBinding
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveFeedRequest
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessOutput
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.TIMED_OUT
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.internal.process.bindLiveFeeds
import dev.jordond.filmstrip.ffmpeg.internal.process.exclusively
import dev.jordond.filmstrip.ffmpeg.internal.process.fileStampOf
import dev.jordond.filmstrip.ffmpeg.internal.process.probeDeadline
import dev.jordond.filmstrip.ffmpeg.internal.process.probeExpiredReason
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.copyPacketCap
import dev.jordond.filmstrip.transform.internal.copy.copyPacketSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
  val bindFeeds: (List<LiveFeedRequest>) -> LiveBinding = ::bindLiveFeeds,
  val deadline: (window: Duration) -> Duration = ::probeDeadline,
  private val runner: ProcessRunner = ProcessRunner(),
) {
  private val locator = ToolchainLocator(config, runner, deadline(Duration.ZERO))
  private val probeLock = Mutex()
  private val probes = mutableMapOf<String, Stamped<MediaInfo>>()

  // Written beside probes by the one read that fills it, and held under the same lock.
  private val colorReads = mutableMapOf<String, Stamped<ColorRead>>()
  private val syncSampleLock = Mutex()
  private val syncSamples = mutableMapOf<Triple<String, FileStamp?, Duration>, Duration?>()
  private val copyPacketLock = Mutex()
  private val copyPacketReads = mutableMapOf<String, PacketWindow>()
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

  // How many times a copy's packets were actually read, counted the same way.
  var copyPacketSpawns: Int = 0
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
   * What this toolchain can encode, measured by [measure] the first time it is asked for, or why it could not be.
   *
   * Held on the runtime, so every engine a process builds for its components shares one measurement
   * of the ladder. The lock is held across the measurement, so two callers arriving together measure
   * once between them. A measurement that failed is not held, so the next caller measures again.
   *
   * @param measure Runs the ladder against the toolchain this runtime resolved.
   */
  suspend fun capabilities(measure: suspend () -> CapabilitiesResult): CapabilitiesResult =
    capabilityLock.withLock {
      capabilities?.let { return@withLock CapabilitiesResult.Success(it) }
      capabilityMeasures++
      measure().also { if (it is CapabilitiesResult.Success) capabilities = it.capabilities }
    }

  suspend fun capture(
    command: List<String>,
    timeout: Duration,
  ): ProcessOutput = runner.capture(command, timeout)

  // An export runs as long as its media does, so it has no deadline.
  suspend fun stream(
    command: List<String>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
  ): Int {
    streamSpawns++
    return runner.run(command, Duration.INFINITE, onStdout, onStderr)
  }

  /**
   * Spawns [command] and reads its stdout as whole frames, for the preview pump.
   *
   * @throws kotlinx.io.IOException When the child never started.
   */
  suspend fun frames(command: List<String>): FrameStream = runner.frames(command)

  /**
   * Reads [path] through ffprobe, answering null when it could not be read.
   *
   * Each file read is cached by path and the file's [FileStamp], so a plan that names the same clip in several places
   * spawns one ffprobe, a re-lowering spawns none, and a file rewritten at the same path is read again. A path that
   * could not be read is not held, so the next caller asks again and finds a file that turned up since. A read still
   * running at its [deadline] is [ProbeAnswer.Expired].
   */
  suspend fun probe(
    toolchain: Toolchain,
    path: String,
  ): ProbeAnswer<MediaInfo?> =
    probeLock.withLock {
      val stamp = stampOf(path)
      probes[path]?.valueAt(stamp)?.let { return@withLock ProbeAnswer.Answered(it) }
      read(toolchain, path, stamp).also { read -> read.held?.let { probes[path] = Stamped(stamp, it) } }
    }

  /**
   * What [path]'s video stream says about its colour, answering null when [path] could not be read.
   *
   * A file [probe] read already answers from that spawn. One a registered prober answered for spawns once for this,
   * since a [MediaInfo] holds no primaries, and is cached the same way. A read still running at its [deadline] is
   * [ProbeAnswer.Expired].
   */
  suspend fun colorRead(
    toolchain: Toolchain,
    path: String,
  ): ProbeAnswer<ColorRead?> =
    probeLock.withLock {
      val stamp = stampOf(path)
      colorReads[path]?.valueAt(stamp)?.let { return@withLock ProbeAnswer.Answered(it) }
      sdColorSpawns++
      val timeout = deadline(Duration.ZERO)
      val output = capture(listOf(toolchain.ffprobe) + PROBE_ARGUMENTS + path, timeout)
      if (output.timedOut) return@withLock expired(ProbeOperation.COLOR_READ, timeout)
      if (!output.started || output.exitCode != 0) return@withLock ProbeAnswer.Answered(null)
      ProbeAnswer.Answered(colorReadOf(output.stdout).also { colorReads[path] = Stamped(stamp, it) })
    }

  /**
   * The sync sample at or before [at] in [path]'s video stream, answering null when there is none.
   *
   * Cached by path, the file's [FileStamp] and cut, negatives included, so the plan, the resolve behind a preview and
   * the export of one edit read the same answer, and a file rewritten at the same path is read again. A read still
   * running at the [deadline] of [SYNC_SAMPLE_WINDOW] is [ProbeAnswer.Expired] and is not held.
   */
  suspend fun syncSampleAtOrBefore(
    toolchain: Toolchain,
    path: String,
    at: Duration,
  ): ProbeAnswer<Duration?> =
    syncSampleLock.withLock {
      val key = Triple(path, stampOf(path), at)
      if (syncSamples.containsKey(key)) return@withLock ProbeAnswer.Answered(syncSamples[key])
      readSyncSample(toolchain, path, at).also { if (it is ProbeAnswer.Answered) syncSamples[key] = it.value }
    }

  /**
   * [path]'s video packets in decode order from [from] to [to], or why ffprobe could not list them.
   *
   * Only the latest window read for each path is held, with the file's [FileStamp], so the plan, the resolve behind a
   * preview and the export of one edit count the same packets, a file rewritten at the same path is read again, and an
   * edit whose window keeps moving holds one listing rather than one per window. A failed read is not held, so the
   * next caller asks again. A read listing more than [dev.jordond.filmstrip.transform.internal.copy.copyPacketCap]
   * packets is stopped there and fails naming the cap, and one still running at the [deadline] of the
   * [copyPacketSpan] it reads fails naming that.
   */
  suspend fun copyPackets(
    toolchain: Toolchain,
    path: String,
    from: Duration,
    to: Duration,
  ): CopyPacketRead =
    copyPacketLock.withLock {
      val stamp = stampOf(path)
      copyPacketReads[path]
        ?.takeIf { it.stamp == stamp && it.from == from && it.to == to }
        ?.let { return@withLock CopyPacketRead.Read(it.packets) }
      readCopyPackets(toolchain, path, from, to).also {
        if (it is CopyPacketRead.Read) copyPacketReads[path] = PacketWindow(stamp, from, to, it.packets)
      }
    }

  /**
   * How many copy packet listings this runtime holds, one at most for each path.
   */
  suspend fun heldCopyPacketReads(): Int = copyPacketLock.withLock { copyPacketReads.size }

  private suspend fun stampOf(path: String): FileStamp? = withContext(Dispatchers.IO) { fileStampOf(path) }

  private class PacketWindow(
    val stamp: FileStamp?,
    val from: Duration,
    val to: Duration,
    val packets: List<CopyPacket>,
  )

  // An answer read off a file, held with the stamp the file had when it was read.
  private class Stamped<T>(
    val stamp: FileStamp?,
    val value: T,
  ) {
    fun valueAt(current: FileStamp?): T? = value.takeIf { stamp == current }
  }

  /**
   * What ffprobe said about a video stream's colour.
   *
   * @property isTaggedBt709 Whether the stream is tagged BT.709.
   * @property decodesToTailFormat Whether the stream decodes to the plain 4:2:0 a clip's tail ends on.
   */
  class ColorRead(
    val sdColor: SdColor?,
    matrix: String?,
    pixelFormat: String?,
  ) {
    val isTaggedBt709: Boolean = matrix == BT709_MATRIX

    val decodesToTailFormat: Boolean = pixelFormat == TAIL_PIXEL_FORMAT
  }

  // The MediaInfo a probe read answered with, which is what the cache holds for it.
  private val ProbeAnswer<MediaInfo?>.held: MediaInfo? get() = (this as? ProbeAnswer.Answered)?.value

  private fun colorReadOf(stdout: String): ColorRead =
    ColorRead(parseSdColor(stdout), parseVideoMatrix(stdout), parseVideoPixelFormat(stdout))

  /**
   * Reads [path] again, replacing whatever was cached for it.
   *
   * For a file this process has just written, which a stamp read inside one tick of the file system's clock could take
   * for the one it replaced. The packets a copy read off the old file are dropped too.
   */
  suspend fun reprobe(
    toolchain: Toolchain,
    path: String,
  ): ProbeAnswer<MediaInfo?> =
    probeLock.withLock {
      copyPacketLock.withLock { copyPacketReads.remove(path) }
      val stamp = stampOf(path)
      read(toolchain, path, stamp, ProbeOperation.OUTPUT_PROBE).also {
        val held = it.held
        if (held == null) {
          probes.remove(path)
          colorReads.remove(path)
        } else {
          probes[path] = Stamped(stamp, held)
        }
      }
    }

  private suspend fun read(
    toolchain: Toolchain,
    path: String,
    stamp: FileStamp?,
    operation: String = ProbeOperation.SOURCE_PROBE,
  ): ProbeAnswer<MediaInfo?> {
    probeSpawns++
    val timeout = deadline(Duration.ZERO)
    val output = capture(listOf(toolchain.ffprobe) + PROBE_ARGUMENTS + path, timeout)
    if (output.timedOut) return expired(operation, timeout)
    if (!output.started || output.exitCode != 0) return ProbeAnswer.Answered(null)
    colorReads[path] = Stamped(stamp, colorReadOf(output.stdout))
    return ProbeAnswer.Answered(parseMediaInfo(output.stdout))
  }

  private suspend fun readSyncSample(
    toolchain: Toolchain,
    path: String,
    at: Duration,
  ): ProbeAnswer<Duration?> {
    syncSampleSpawns++
    val timeout = deadline(SYNC_SAMPLE_WINDOW)
    val output = capture(syncSampleArguments(toolchain, path, at), timeout)
    if (output.timedOut) return expired(ProbeOperation.SYNC_SAMPLE_READ, timeout)
    return ProbeAnswer.Answered(
      if (output.started && output.exitCode == 0) parseSyncSample(output.stdout, at) else null,
    )
  }

  private suspend fun readCopyPackets(
    toolchain: Toolchain,
    path: String,
    from: Duration,
    to: Duration,
  ): CopyPacketRead {
    copyPacketSpawns++
    val cap = copyPacketCap(from, to)
    val timeout = deadline(copyPacketSpan(from, to))
    val packets = mutableListOf<CopyPacket>()
    val code =
      try {
        runner.run(
          copyPacketArguments(toolchain, path, from, to),
          timeout,
          onStdout = { line ->
            parseCopyPacket(line)?.let { packets += it }
            if (packets.size > cap) throw PacketCapReached()
          },
          onStderr = {},
        )
      } catch (_: PacketCapReached) {
        return CopyPacketRead.Failed(packetCapReason(cap, from, to))
      }
    return when (code) {
      0 -> {
        CopyPacketRead.Read(packets)
      }
      TIMED_OUT -> {
        CopyPacketRead.Expired(
          ProbeOperation.COPY_PACKET_READ,
          timeout,
          probeExpiredReason(FFPROBE, timeout),
        )
      }
      else -> {
        CopyPacketRead.Failed(UNLISTED_PACKETS)
      }
    }
  }

  private fun expired(
    operation: String,
    timeout: Duration,
  ): ProbeAnswer.Expired = ProbeAnswer.Expired(operation, timeout, probeExpiredReason(FFPROBE, timeout))

  // Thrown out of the line reader to stop a packet read at its cap, which also stops the child.
  private class PacketCapReached : RuntimeException()

  /**
   * Resolves the toolchain and then reads [path], for a caller that holds no [Toolchain] of its own.
   */
  suspend fun probeSource(path: String): ProbeOutcome =
    when (val result = toolchain()) {
      is ToolchainResult.Unavailable -> {
        ProbeOutcome.NoToolchain(result.error)
      }
      is ToolchainResult.Available -> {
        when (val read = probe(result.toolchain, path)) {
          is ProbeAnswer.Expired -> ProbeOutcome.Expired(read.operation, read.deadline, read.reason)
          is ProbeAnswer.Answered -> read.value?.let { ProbeOutcome.Read(it) } ?: ProbeOutcome.Unreadable
        }
      }
    }

  companion object {
    private const val BT709_MATRIX = "bt709"
    private const val TAIL_PIXEL_FORMAT = "yuv420p"
    private const val FFPROBE = "ffprobe"

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
      bindFeeds: (List<LiveFeedRequest>) -> LiveBinding = ::bindLiveFeeds,
      deadline: (window: Duration) -> Duration = ::probeDeadline,
      runner: ProcessRunner = ProcessRunner(),
    ): FfmpegRuntime = FfmpegRuntime(config, bytesRoot, bindFeeds, deadline, runner)
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

  class Expired(
    val operation: String,
    val deadline: Duration,
    val reason: String,
  ) : ProbeOutcome
}
