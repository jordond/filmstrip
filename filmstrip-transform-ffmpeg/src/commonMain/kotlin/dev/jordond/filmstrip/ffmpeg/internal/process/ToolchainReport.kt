package dev.jordond.filmstrip.ffmpeg.internal.process

// Pure parsing of what the binaries print. No spawning here, so every rule below is unit-testable
// against captured output.

// The lowest version this backend will use. Below 8.0 the mov demuxer reads no HEIF at all, or reads one without its
// tile and alpha streams, so an overlay picture would decode as something other than what its file holds.
internal const val MINIMUM_MAJOR: Int = 8
internal const val MINIMUM_MINOR: Int = 0

// The libavformat major and minor ffmpeg 8.0 ships, which stand in for the release on a build that names none.
internal const val MINIMUM_LIBAVFORMAT_MAJOR: Int = 62
internal const val MINIMUM_LIBAVFORMAT_MINOR: Int = 3

/**
 * The version line as printed, and what could be made of it.
 *
 * @property banner Line one of `ffmpeg -version`, verbatim, for a message a human reads.
 * @property major Null when the version is not a release number, which a git build never is.
 * @property libavformatMajor The major of the `libavformat` line `ffmpeg -version` prints, or null when it printed
 * none.
 * @property libavformatMinor The minor of that same line, null exactly when [libavformatMajor] is.
 */
internal class FfmpegVersion(
  val banner: String,
  val major: Int?,
  val minor: Int?,
  val libavformatMajor: Int? = null,
  val libavformatMinor: Int? = null,
) {
  /**
   * Whether this build is at or past the floor, ffmpeg [MINIMUM_MAJOR].[MINIMUM_MINOR].
   *
   * A build that names its release is held to it by [major] and [minor]. One that names none, such as an `N-` snapshot
   * of master or a dated git build, is held to it by [libavformatMajor] and [libavformatMinor] against
   * [MINIMUM_LIBAVFORMAT_MAJOR].[MINIMUM_LIBAVFORMAT_MINOR]. A build that prints neither does not meet it, since
   * nothing it prints says it does.
   */
  fun meetsFloor(): Boolean {
    major?.let { return it > MINIMUM_MAJOR || (it == MINIMUM_MAJOR && (minor ?: 0) >= MINIMUM_MINOR) }
    val library = libavformatMajor ?: return false
    return library > MINIMUM_LIBAVFORMAT_MAJOR ||
      (library == MINIMUM_LIBAVFORMAT_MAJOR && (libavformatMinor ?: 0) >= MINIMUM_LIBAVFORMAT_MINOR)
  }

  // Whether the build printed a release or a libavformat version to hold against the floor.
  val readable: Boolean
    get() = major != null || libavformatMajor != null

  val printed: String
    get() = banner.removePrefix("ffmpeg version ").substringBefore(' ')
}

// Line one reads `ffmpeg version 9.0.1 Copyright ...`, or `n8.0.1-42-gabcdef`, or `N-121284-g4c9e0eb`,
// or `8.0.1-3ubuntu2`. Only a leading `<digits>.<digits>` is a version. Everything else is a build
// identifier and is treated as unknown rather than as zero. The library lines below it read
// `libavformat    62.  3.100 / 62.  3.100`, and the first two numbers of that one are its major and minor.
internal fun parseVersion(output: String): FfmpegVersion {
  val banner =
    output
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      ?.trim()
      .orEmpty()
  val token = banner.removePrefix("ffmpeg version ").substringBefore(' ').removePrefix("n")
  val match = VERSION_PATTERN.find(token)
  val library = LIBAVFORMAT_PATTERN.find(output)?.groupValues
  return FfmpegVersion(
    banner = banner,
    major = match?.groupValues?.get(1)?.toIntOrNull(),
    minor = match?.groupValues?.get(2)?.toIntOrNull(),
    libavformatMajor = library?.get(1)?.toIntOrNull(),
    libavformatMinor = library?.get(2)?.toIntOrNull(),
  )
}

private val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)""")
private val LIBAVFORMAT_PATTERN = Regex("""^\s*libavformat\s+(\d+)\.\s*(\d+)\.""", RegexOption.MULTILINE)

/**
 * The names in an `ffmpeg -filters` or `ffmpeg -encoders` listing.
 *
 * Both print a flags column, the name, then a description. The header ends at a line of dashes, so
 * anything before that is skipped rather than matched around.
 *
 * Gate on these rather than on the version. `overlay` appears in no configure flag, and
 * `ffmpeg -h filter=drawtext` exits 0 for a filter that does not exist, so neither the
 * `configuration:` line nor an exit code answers the question.
 */
internal fun parseNameListing(output: String): Set<String> {
  val body = output.substringAfter("\n --", missingDelimiterValue = output)
  return body
    .lineSequence()
    .mapNotNull { line ->
      val parts = line.trim().split(WHITESPACE)
      if (parts.size < 2) return@mapNotNull null
      parts[1].takeIf { it.isNotBlank() && it.first().isLetter() }
    }.toSet()
}

/**
 * The protocols `-protocols` lists under its input heading, one name a line above its output heading.
 */
internal fun parseInputProtocols(output: String): Set<String> =
  output
    .substringAfter("Input:", missingDelimiterValue = "")
    .substringBefore("Output:")
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

/**
 * The bitstream filter a copy that ends before its source does drops its video packets past the grid with.
 *
 * Every build this backend runs on carries it, and [ToolchainLocator] refuses one that does not.
 */
internal const val PACKET_DROP_FILTER: String = "noise"

/**
 * The names `-bsfs` lists, one a line under its heading.
 */
internal fun parseBitstreamFilters(output: String): Set<String> =
  output
    .substringAfter("Bitstream filters:", missingDelimiterValue = "")
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

private val WHITESPACE = Regex("""\s+""")
