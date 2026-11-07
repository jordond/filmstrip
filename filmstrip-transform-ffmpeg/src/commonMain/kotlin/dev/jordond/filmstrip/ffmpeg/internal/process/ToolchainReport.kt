package dev.jordond.filmstrip.ffmpeg.internal.process

// Pure parsing of what the binaries print. No spawning here, so every rule below is unit-testable
// against captured output.

// The lowest version this backend will use. Below 8.0 the mov demuxer reads no HEIF at all, or reads one without its
// tile and alpha streams, so an overlay picture would decode as something other than what its file holds.
internal const val MINIMUM_MAJOR: Int = 8
internal const val MINIMUM_MINOR: Int = 0

/**
 * The version line as printed, and what could be made of it.
 *
 * @property banner Line one of `ffmpeg -version`, verbatim, for a message a human reads.
 * @property major Null when the version is not a release number, which a git build never is.
 */
internal class FfmpegVersion(
  val banner: String,
  val major: Int?,
  val minor: Int?,
) {
  // A build that does not name a release, such as an `N-` snapshot of master, is taken as one newer than the floor.
  // Its banner carries nothing to compare, and refusing it would refuse every nightly.
  val isUsable: Boolean
    get() {
      val major = major ?: return true
      if (major > MINIMUM_MAJOR) return true
      return major == MINIMUM_MAJOR && (minor ?: 0) >= MINIMUM_MINOR
    }

  val printed: String
    get() = banner.removePrefix("ffmpeg version ").substringBefore(' ')
}

// Line one reads `ffmpeg version 9.0.1 Copyright ...`, or `n8.0.1-42-gabcdef`, or `N-121284-g4c9e0eb`,
// or `8.0.1-3ubuntu2`. Only a leading `<digits>.<digits>` is a version. Everything else is a build
// identifier and is treated as unknown rather than as zero.
internal fun parseVersion(output: String): FfmpegVersion {
  val banner =
    output
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      ?.trim()
      .orEmpty()
  val token = banner.removePrefix("ffmpeg version ").substringBefore(' ').removePrefix("n")
  val match = VERSION_PATTERN.find(token)
  return FfmpegVersion(
    banner = banner,
    major = match?.groupValues?.get(1)?.toIntOrNull(),
    minor = match?.groupValues?.get(2)?.toIntOrNull(),
  )
}

private val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)""")

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

private val WHITESPACE = Regex("""\s+""")
