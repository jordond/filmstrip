package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.media.MediaBytesFile
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * The directory a buffer lands under, and where it goes instead when that directory is refused.
 *
 * Once [named] has been refused and the fallback took a file, every later buffer goes to the fallback, so one buffer
 * keeps one path for the rest of the process. [MediaBytesFile] judges a fallback the same as [named], and a fallback
 * that is refused as well fails with its [MediaBytesFileFailure].
 *
 * @param named The directory tried first.
 * @param fallback Makes the directory used once [named] is refused, called at most once if it succeeds. With none, a
 * refusal of [named] is the answer.
 */
internal class BytesRoot(
  private val named: String,
  fallback: (() -> String)? = null,
) {
  private val fallbackRoot: Lazy<String>? = fallback?.let { lazy(it) }

  @Volatile
  private var namedRefused = false

  /**
   * The path holding [source]'s bytes.
   *
   * @throws MediaBytesFileFailure when neither directory could take the file.
   */
  fun fileFor(source: MediaSource.Bytes): String {
    if (namedRefused) return MediaBytesFile.fileFor(checkNotNull(fallbackRoot).value, source)
    return try {
      MediaBytesFile.fileFor(named, source)
    } catch (refused: MediaBytesFileFailure) {
      val root = fallbackRootOr(refused)
      MediaBytesFile.fileFor(root, source).also { namedRefused = true }
    }
  }

  private fun fallbackRootOr(refused: MediaBytesFileFailure): String {
    val fallback = fallbackRoot ?: throw refused
    return try {
      fallback.value
    } catch (failure: Exception) {
      // The platform throws its own kinds here, and the refusal is what tells a caller what went wrong.
      refused.addSuppressed(failure)
      throw refused
    }
  }
}

/**
 * The directory a buffer lands under unless a caller names another.
 *
 * Each user gets one directory, since the temporary directory is shared on Linux and another user's would refuse a
 * write. That name is easy to guess and the sticky bit stops its victim removing what another user planted there, so a
 * refused one falls back on [freshPrivateRoot].
 */
internal val DEFAULT_BYTES_ROOT: BytesRoot =
  BytesRoot(
    named = Path(SystemTemporaryDirectory, "filmstrip-${System.getProperty("user.name")}").toString(),
    fallback = ::freshPrivateRoot,
  )

/**
 * Makes a new directory under the temporary directory for this process alone, and removes it at JVM shutdown.
 *
 * The JVM creates it `0700` under a random name. A process killed outright leaves it behind for the OS to clear.
 *
 * @throws java.io.IOException when the directory can't be made.
 */
@OptIn(ExperimentalPathApi::class)
internal fun freshPrivateRoot(): String {
  val root = Files.createTempDirectory("filmstrip-${System.getProperty("user.name")}-")
  Runtime.getRuntime().addShutdownHook(
    thread(start = false, name = "filmstrip-bytes-root") {
      try {
        root.deleteRecursively()
      } catch (_: IOException) {
        // The JVM is going away, so there is nobody left to tell and the OS clears what stays.
      }
    },
  )
  return root.toString()
}
