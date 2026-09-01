package dev.jordond.filmstrip.media3.internal

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where bytes a caller handed over are written so media3 can open them.
 *
 * A `MediaItem` names a URI and nothing else, so an in-memory source has to become a file before
 * anything can be lowered. A file is named after a digest of the bytes it holds, so the same buffer
 * lowered again finds the file already there and the export that follows writes no copy of its own.
 * The directory is emptied the first time a process reaches it, which is what stops the files an
 * earlier run left behind outliving it.
 */
internal object Media3Scratch {
  private val lock = Any()

  private var swept = false

  /**
   * The file under [context]'s cache holding [bytes], written under [extension] if it is not there
   * already.
   *
   * @throws IOException when the directory or the file could not be written.
   */
  fun fileFor(
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
    }

    return file
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

  private const val DIRECTORY = "filmstrip-scratch"

  private const val DIGEST = "SHA-256"

  // Enough of the digest that two sources cannot land on one name, short enough that the name stays
  // readable in a log.
  private const val NAME_BYTES = 16

  private const val HEX = 16
}
