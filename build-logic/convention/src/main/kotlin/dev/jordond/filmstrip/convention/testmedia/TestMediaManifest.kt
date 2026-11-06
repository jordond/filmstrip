package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest

/**
 * One published fixture: the name a test opens it by, and the bytes it must be.
 */
data class TestMediaEntry(
  val fileName: String,
  val sha256: String,
  val bytes: Long,
) {
  val extension: String get() = fileName.substringAfterLast('.')
}

/**
 * The checked-in list of fixtures a module reads, and the contract for what they contain.
 *
 * A generated clip is a function of the ffmpeg that encoded it, so rolling one per runner makes
 * every test's input a moving target. The manifest pins the bytes instead, which is what lets a
 * test assert on a pixel or a file size at all.
 *
 * Objects are keyed by digest rather than by name, so republishing a changed clip writes a new
 * object and leaves the old one where an earlier commit's manifest still points at it. The sample's
 * own clips sit at the bucket root under their own names, which suits them because nothing asserts
 * on their pixels.
 */
object TestMediaManifest {
  const val BUCKET: String = "filmstrip-media"

  const val HOST: String = "https://filmstrip-media.jordond.dev"

  const val FILE_NAME: String = "test-fixtures.txt"

  private const val PREFIX = "test-fixtures"

  private const val BUILD_HEADER = "# ffmpeg "

  private const val VERSION_PREFIX = "ffmpeg version "

  private const val COMMENT = '#'

  private const val FIELDS = 3

  private const val HEX_MASK = 0xFF

  private const val HEX_RADIX = 16

  private const val PAD = 2

  private val X264_SEI = Regex("""x264 - (core .+?) - H\.264""")

  private val X265_SEI = Regex("""x265 \(build \d+\) - (.+?) - H\.265""")

  /**
   * The object [entry] is published under, its digest with the extension its file name carries. A clip lands at
   * `test-fixtures/<sha256>.mp4`.
   */
  fun keyFor(entry: TestMediaEntry): String = "$PREFIX/${entry.sha256}.${entry.extension}"

  fun urlFor(entry: TestMediaEntry): String = "$HOST/${keyFor(entry)}"

  fun read(file: File): List<TestMediaEntry> {
    if (!file.isFile) {
      throw GradleException(
        "no fixture manifest at ${file.absolutePath}. Roll the clips with the module's " +
          "generate task, then publish them.",
      )
    }

    return file
      .readLines()
      .map { it.substringBefore(COMMENT).trim() }
      .filter { it.isNotEmpty() }
      .map { line ->
        val fields = line.split(" ").filter { it.isNotEmpty() }
        if (fields.size != FIELDS) {
          throw GradleException("${file.name} has a row that is not `name sha256 bytes`: $line")
        }
        TestMediaEntry(fileName = fields[0], sha256 = fields[1], bytes = fields[2].toLong())
      }
  }

  /**
   * The build that encodes fixtures, naming ffmpeg, both encoders and the host architecture, since a rebuild of either
   * encoder or a build for another architecture changes bytes under the same version strings.
   *
   * ffmpeg comes from the first line of `ffmpeg -version`, such as `8.0.1-3ubuntu2` from
   * `ffmpeg version 8.0.1-3ubuntu2 Copyright (c) 2000-2025 the FFmpeg developers`. A line in another shape is kept
   * whole, trimmed. Each encoder comes from the SEI of a raw stream it wrote, such as `core 165 r3222 b35605a` for
   * libx264 and `4.1+1-1d117be:[Linux][GCC 15.2.0][64 bit] 10bit` for libx265. Throws when a stream has no such SEI.
   * [architecture] closes the record, such as `arch aarch64`.
   *
   * Ubuntu's libx264 writes only `core 165` with no revision, and nothing else in its stream names the rebuild. Two
   * rebuilds on one core read as one build, so [requireSameBuild] lets their changed bytes through. This is a limit of
   * that libx264.
   */
  fun buildOf(
    versionLine: String,
    x264Stream: String,
    x265Stream: String,
    architecture: String,
  ): String {
    val line = versionLine.trim()
    val ffmpeg = if (line.startsWith(VERSION_PREFIX)) line.removePrefix(VERSION_PREFIX).substringBefore(' ') else line
    val encoders = "libx264 ${encoderIn(x264Stream, X264_SEI)} libx265 ${encoderIn(x265Stream, X265_SEI)}"
    return "$ffmpeg $encoders arch $architecture"
  }

  private fun encoderIn(
    stream: String,
    sei: Regex,
  ): String =
    sei.find(stream)?.groupValues?.get(1)
      ?: throw GradleException("a probe encode wrote no SEI matching ${sei.pattern}, so its encoder has no identity")

  /**
   * The header line naming [build], which a pinned manifest and the generate task's list both carry and [readBuild]
   * reads back.
   */
  fun buildHeader(build: String): String = "$BUILD_HEADER$build"

  /**
   * The build [file] says made its fixtures, read from its `# ffmpeg <build>` header, or null when it has none.
   * A manifest written before the header existed reads as null, an unknown build.
   */
  fun readBuild(file: File): String? {
    if (!file.isFile) return null
    return file
      .readLines()
      .firstOrNull { it.startsWith(BUILD_HEADER) }
      ?.removePrefix(BUILD_HEADER)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  /**
   * Writes [entries] sorted by name under a header naming [build], the [buildOf] that made them.
   */
  fun write(
    file: File,
    entries: List<TestMediaEntry>,
    build: String?,
  ) {
    val text =
      buildString {
        appendLine("$COMMENT filmstrip test fixtures, served from $HOST/$PREFIX")
        appendLine("$COMMENT Written by the module's publish task. Commit it with the clips it names.")
        build?.let { appendLine(buildHeader(it)) }
        appendLine("$COMMENT name sha256 bytes")
        entries.sortedBy { it.fileName }.forEach {
          appendLine("${it.fileName} ${it.sha256} ${it.bytes}")
        }
      }

    file.parentFile?.mkdirs()
    file.writeText(text)
  }

  fun digestOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }

    return digest.digest().joinToString("") { byte ->
      (byte.toInt() and HEX_MASK).toString(HEX_RADIX).padStart(PAD, '0')
    }
  }
}
