package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration

/**
 * A usable pair of binaries, and what the ffmpeg one can do.
 *
 * @property filters Every filter this build carries. Gate on it: a prebuilt ffmpeg without
 *   libfreetype has no `drawtext` at all, and that is the common case rather than an exotic one.
 * @property inputProtocols Every protocol this build can read an input over.
 */
internal class Toolchain(
  val ffmpeg: String,
  val ffprobe: String,
  val version: FfmpegVersion,
  val filters: Set<String>,
  val encoders: Set<String>,
  val inputProtocols: Set<String> = emptySet(),
) {
  fun hasFilter(name: String): Boolean = name in filters

  fun hasEncoder(name: String): Boolean = name in encoders

  fun hasInputProtocol(name: String): Boolean = name in inputProtocols
}

/**
 * Whether there is anything to run.
 *
 * A sealed value whose failure is a [FilmstripError], which is not a `Throwable`.
 */
internal sealed interface ToolchainResult {
  class Available(
    val toolchain: Toolchain,
  ) : ToolchainResult

  class Unavailable(
    val error: FilmstripError.ToolchainMissing,
  ) : ToolchainResult
}

/**
 * Finds the binaries and remembers them once found.
 *
 * A missing or too old ffmpeg is looked for again on the next call, so a host that installs one or
 * sets `FILMSTRIP_FFMPEG` after the first attempt is picked up without a new runtime. A build without the
 * [PACKET_DROP_FILTER] bitstream filter is refused the same way, here rather than part way through an export. So is one
 * still answering its version or one of its listings at [deadline], naming the option it hung on.
 *
 * Resolution is [FfmpegConfig.executablePath], then `FILMSTRIP_FFMPEG`, then a `PATH` search, and
 * the result is always made absolute before it is spawned.
 *
 * @param deadline How long each of the binary's listings may run before it is stopped.
 */
internal class ToolchainLocator(
  private val config: FfmpegConfig,
  private val runner: ProcessRunner,
  private val deadline: Duration = probeDeadline(),
) {
  private val lock = Mutex()
  private var resolved: ToolchainResult.Available? = null

  suspend fun toolchain(): ToolchainResult =
    lock.withLock {
      resolved ?: resolve().also { if (it is ToolchainResult.Available) resolved = it }
    }

  private suspend fun resolve(): ToolchainResult {
    val ffmpeg =
      locate(config.executablePath, "FILMSTRIP_FFMPEG", FFMPEG)
        ?: return ToolchainResult.Unavailable(missing(FFMPEG, null))

    val version = runner.capture(listOf(ffmpeg, "-version"), deadline)
    if (!version.started) return ToolchainResult.Unavailable(missing(FFMPEG, null))
    if (version.timedOut) return ToolchainResult.Unavailable(hung(VERSION, null))

    val parsed = parseVersion(version.stdout)
    if (!parsed.meetsFloor()) {
      val error = if (parsed.readable) missing(FFMPEG, parsed.printed) else unreadable(FFMPEG, parsed.printed)
      return ToolchainResult.Unavailable(error)
    }

    val ffprobe =
      locate(config.probePath, "FILMSTRIP_FFPROBE", FFPROBE)
        ?: siblingOf(ffmpeg)
        ?: return ToolchainResult.Unavailable(missing(FFPROBE, null))

    val listings = mutableMapOf<String, String>()
    for (listing in LISTINGS) {
      val output = runner.capture(listOf(ffmpeg, "-hide_banner", listing), deadline)
      if (output.timedOut) return ToolchainResult.Unavailable(hung(listing, parsed.printed))
      if (listing == BITSTREAM_FILTERS && PACKET_DROP_FILTER !in parseBitstreamFilters(output.stdout)) {
        return ToolchainResult.Unavailable(lacksPacketDrop(parsed.printed))
      }
      listings[listing] = output.stdout
    }

    return ToolchainResult.Available(
      Toolchain(
        ffmpeg = ffmpeg,
        ffprobe = ffprobe,
        version = parsed,
        filters = parseNameListing(listings.getValue(FILTERS)),
        encoders = parseNameListing(listings.getValue(ENCODERS)),
        inputProtocols = parseInputProtocols(listings.getValue(PROTOCOLS)),
      ),
    )
  }

  private fun locate(
    configured: String?,
    variable: String,
    name: String,
  ): String? {
    configured?.let { return existingFile(it) }
    environmentVariable(variable)?.let { return existingFile(it) }
    return searchPath(name)
  }

  private fun searchPath(name: String): String? =
    pathEntries()
      .asSequence()
      .flatMap { entry -> executableSuffixes().map { suffix -> "$entry/$name$suffix" } }
      .firstNotNullOfOrNull(::existingFile)

  private fun siblingOf(ffmpeg: String): String? {
    val directory = ffmpeg.substringBeforeLast('/', missingDelimiterValue = "")
    if (directory.isEmpty()) return null
    return executableSuffixes().firstNotNullOfOrNull { existingFile("$directory/$FFPROBE$it") }
  }

  private fun existingFile(path: String): String? {
    val metadata = SystemFileSystem.metadataOrNull(Path(path)) ?: return null
    return if (metadata.isRegularFile) absolutePathOf(path) else null
  }

  private fun missing(
    tool: String,
    found: String?,
  ): FilmstripError.ToolchainMissing =
    FilmstripError.ToolchainMissing(
      tool = tool,
      foundVersion = found,
      message =
        if (found == null) {
          "`$tool` was not found. filmstrip ships no ffmpeg and drives the one on your machine, " +
            "so install ffmpeg $MINIMUM_MAJOR.$MINIMUM_MINOR or newer, or point " +
            "FfmpegConfig.executablePath at it, or set FILMSTRIP_FFMPEG."
        } else {
          "`$tool` $found is too old. filmstrip needs ffmpeg $MINIMUM_MAJOR.$MINIMUM_MINOR or newer, " +
            "because below it HEIF and AVIF pictures are read without their tiles or alpha."
        },
    )

  private fun unreadable(
    tool: String,
    found: String,
  ): FilmstripError.ToolchainMissing =
    FilmstripError.ToolchainMissing(
      tool = tool,
      foundVersion = found,
      message =
        "`$tool` $found names no release and its libavformat version could not be read, so filmstrip cannot tell " +
          "whether it is ffmpeg $MINIMUM_MAJOR.$MINIMUM_MINOR or newer. Install a release build of ffmpeg " +
          "$MINIMUM_MAJOR.$MINIMUM_MINOR or newer, or point FfmpegConfig.executablePath at one, or set " +
          "FILMSTRIP_FFMPEG.",
    )

  private fun lacksPacketDrop(found: String): FilmstripError.ToolchainMissing =
    FilmstripError.ToolchainMissing(
      tool = FFMPEG,
      foundVersion = found,
      message =
        "`$FFMPEG` $found has no `$PACKET_DROP_FILTER` bitstream filter, which a copy that ends before its source " +
          "does needs to stop its video on the last frame it keeps. Install an ffmpeg $MINIMUM_MAJOR.$MINIMUM_MINOR " +
          "or newer build that carries it, or point FfmpegConfig.executablePath at one, or set FILMSTRIP_FFMPEG.",
    )

  // A binary that never answers one of its own listings is broken whatever version it is, so it is refused naming the
  // listing rather than read as one that carries nothing.
  private fun hung(
    option: String,
    found: String?,
  ): FilmstripError.ToolchainMissing =
    FilmstripError.ToolchainMissing(
      tool = FFMPEG,
      foundVersion = found,
      message =
        "`$FFMPEG $option` was still running after $deadline and was stopped, so filmstrip could not tell what this " +
          "build can do. Point FfmpegConfig.executablePath at an ffmpeg $MINIMUM_MAJOR.$MINIMUM_MINOR or newer " +
          "that answers it, or set FILMSTRIP_FFMPEG.",
    )

  private companion object {
    const val FFMPEG = "ffmpeg"
    const val FFPROBE = "ffprobe"
    const val VERSION = "-version"
    const val BITSTREAM_FILTERS = "-bsfs"
    const val FILTERS = "-filters"
    const val ENCODERS = "-encoders"
    const val PROTOCOLS = "-protocols"

    // Asked in this order, so a build without the bitstream filter is refused for it before the rest are asked.
    val LISTINGS = listOf(BITSTREAM_FILTERS, FILTERS, ENCODERS, PROTOCOLS)
  }
}
