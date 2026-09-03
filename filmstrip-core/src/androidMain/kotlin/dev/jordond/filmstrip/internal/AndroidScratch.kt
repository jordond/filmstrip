package dev.jordond.filmstrip.internal

import android.content.Context
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.AndroidScratch.BUDGET_BYTES
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where bytes a caller handed over are written so the platform can open them as a file.
 *
 * `MediaMetadataRetriever` and `MediaItem` both name a path or a URI and nothing else, so an
 * in-memory source has to become a file before it can be probed or lowered. A file is named after a
 * digest of the bytes it holds, so the probe and the export that follows it land on the same name
 * and the buffer is written once however many times it is read.
 *
 * The directory is emptied the first time a process reaches it, which is what stops the files an
 * earlier run left behind outliving it, and held under [BUDGET_BYTES] after that, which is what
 * stops a process that reads a new buffer for every edit from keeping all of them until it dies.
 */
@InternalFilmstripApi
public object AndroidScratch {
  private val lock = Any()

  private var swept = false

  /**
   * The file under [context]'s cache holding [source]'s bytes, named for the container its hint
   * claims.
   *
   * Every caller that needs [source] as a file goes through here rather than naming the extension
   * itself, so a source probed and then exported is written to one file rather than two.
   *
   * @throws IOException when the directory or the file could not be written.
   */
  public fun fileFor(
    context: Context,
    source: MediaSource.Bytes,
  ): File = fileFor(context, source.bytes, source.hint.extension())

  /**
   * The file under [context]'s cache holding [bytes], written under [extension] if it is not there
   * already.
   *
   * @throws IOException when the directory or the file could not be written.
   */
  public fun fileFor(
    context: Context,
    bytes: ByteArray,
    extension: String,
  ): File {
    val directory = context.scratchDirectory()
    val file = File(directory, "${digestOf(bytes)}.$extension")

    synchronized(lock) {
      if (file.length() != bytes.size.toLong()) {
        // Written beside the name it is claiming and moved onto it, so a run that dies part way
        // through leaves nothing a later one would mistake for a whole file.
        val partial = File(directory, "${file.name}.partial")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) throw IOException("${partial.path} could not be moved onto ${file.path}.")
      }

      // Eviction takes the oldest first, so touching the file on the way out is what puts whatever
      // is about to be read last in line.
      file.setLastModified(System.currentTimeMillis())
      directory.trimTo(BUDGET_BYTES)
    }

    return file
  }

  /**
   * Deletes the least recently used files until what is left fits in [budget].
   *
   * Nothing tells this when a read has finished with a file, so the file handed out most recently
   * is the one eviction reaches last rather than one it is told to spare.
   */
  private fun File.trimTo(budget: Long) {
    val files = listFiles()?.sortedByDescending { it.lastModified() } ?: return

    var kept = 0L
    for (file in files) {
      val size = file.length()
      if (kept + size <= budget) kept += size else file.delete()
    }
  }

  private fun Context.scratchDirectory(): File {
    val directory = File(cacheDir, DIRECTORY)

    synchronized(lock) {
      if (!swept) {
        directory.listFiles()?.forEach { it.delete() }
        swept = true
      }
    }

    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IOException("The scratch directory ${directory.path} could not be created.")
    }
    return directory
  }

  private fun digestOf(bytes: ByteArray): String =
    MessageDigest
      .getInstance(DIGEST)
      .digest(bytes)
      .take(NAME_BYTES)
      .joinToString("") { byte -> byte.toUByte().toString(HEX).padStart(2, '0') }

  /**
   * The extension a buffer with this hint is written under.
   *
   * Both the retriever and media3 sniff a container rather than trusting the name, so an unhinted
   * buffer is written under a name that claims nothing.
   */
  private fun FormatHint?.extension(): String =
    when (this) {
      FormatHint.Mp4 -> "mp4"
      FormatHint.Mov -> "mov"
      FormatHint.M4a -> "m4a"
      FormatHint.ThreeGp -> "3gp"
      null -> "tmp"
    }

  private const val DIRECTORY = "filmstrip-scratch"

  // Room for a handful of buffers of the size a caller hands over, on a directory the platform is
  // free to reclaim anyway. A file still being read from is the newest, so it is the last one this
  // reaches.
  private const val BUDGET_BYTES = 256L * 1024 * 1024

  private const val DIGEST = "SHA-256"

  // Enough of the digest that two sources cannot land on one name, short enough that the name stays
  // readable in a log.
  private const val NAME_BYTES = 16

  private const val HEX = 16
}
