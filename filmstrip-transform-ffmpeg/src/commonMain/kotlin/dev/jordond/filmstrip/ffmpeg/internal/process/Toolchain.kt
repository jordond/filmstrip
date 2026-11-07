package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
 * sets `FILMSTRIP_FFMPEG` after the first attempt is picked up without a new runtime.
 *
 * Resolution is [FfmpegConfig.executablePath], then `FILMSTRIP_FFMPEG`, then a `PATH` search, and
 * the result is always made absolute before it is spawned.
 */
internal class ToolchainLocator(
  private val config: FfmpegConfig,
  private val runner: ProcessRunner,
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

    val version = runner.capture(listOf(ffmpeg, "-version"))
    if (!version.started) return ToolchainResult.Unavailable(missing(FFMPEG, null))

    val parsed = parseVersion(version.stdout)
    if (!parsed.isUsable) return ToolchainResult.Unavailable(missing(FFMPEG, parsed.printed))

    val ffprobe =
      locate(config.probePath, "FILMSTRIP_FFPROBE", FFPROBE)
        ?: siblingOf(ffmpeg)
        ?: return ToolchainResult.Unavailable(missing(FFPROBE, null))

    val filters = runner.capture(listOf(ffmpeg, "-hide_banner", "-filters"))
    val encoders = runner.capture(listOf(ffmpeg, "-hide_banner", "-encoders"))
    val protocols = runner.capture(listOf(ffmpeg, "-hide_banner", "-protocols"))

    return ToolchainResult.Available(
      Toolchain(
        ffmpeg = ffmpeg,
        ffprobe = ffprobe,
        version = parsed,
        filters = parseNameListing(filters.stdout),
        encoders = parseNameListing(encoders.stdout),
        inputProtocols = parseInputProtocols(protocols.stdout),
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

  private companion object {
    const val FFMPEG = "ffmpeg"
    const val FFPROBE = "ffprobe"
  }
}
