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
)

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

    private const val COMMENT = '#'

    private const val FIELDS = 3

    private const val HEX_MASK = 0xFF

    private const val HEX_RADIX = 16

    private const val PAD = 2

    fun keyFor(sha256: String): String = "$PREFIX/$sha256.mp4"

    fun urlFor(sha256: String): String = "$HOST/${keyFor(sha256)}"

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

    fun write(
        file: File,
        entries: List<TestMediaEntry>,
    ) {
        val text =
            buildString {
                appendLine("$COMMENT filmstrip test fixtures, served from $HOST/$PREFIX")
                appendLine("$COMMENT Written by the module's publish task. Commit it with the clips it names.")
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
