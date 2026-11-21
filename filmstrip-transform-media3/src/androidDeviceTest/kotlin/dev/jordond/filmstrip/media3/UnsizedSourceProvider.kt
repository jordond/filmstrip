package dev.jordond.filmstrip.media3

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Hands out a file from the test APK's cache without telling its reader how long it is.
 *
 * A [PIPE] URI streams the file through a pipe, which has no length and cannot seek. An [UNSIZED] URI opens the file
 * itself and declares no length, so a reader has to ask the descriptor. Neither answers a query for its size.
 */
class UnsizedSourceProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun openAssetFile(
    uri: Uri,
    mode: String,
  ): AssetFileDescriptor {
    val (kind, name) = uri.pathSegments.takeIf { it.size == 2 } ?: throw FileNotFoundException("no file named in $uri")
    val file = File(requireNotNull(context).cacheDir, name)
    if (!file.exists()) throw FileNotFoundException("$file does not exist")
    val descriptor =
      when (kind) {
        PIPE -> pipeOf(file)
        UNSIZED -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        else -> throw FileNotFoundException("$kind is neither $PIPE nor $UNSIZED")
      }
    return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
  }

  // A reader that stops early closes its end, which ends the write with an IOException nobody waits on.
  private fun pipeOf(file: File): ParcelFileDescriptor {
    val (read, write) = ParcelFileDescriptor.createPipe()
    thread(isDaemon = true) {
      runCatching {
        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
          file.inputStream().use { it.copyTo(out) }
        }
      }
    }
    return read
  }

  override fun getType(uri: Uri): String = "video/mp4"

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun insert(
    uri: Uri,
    values: ContentValues?,
  ): Uri? = null

  override fun delete(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  companion object {
    private const val AUTHORITY = "dev.jordond.filmstrip.media3.test.unsized"
    const val PIPE = "pipe"
    const val UNSIZED = "unsized"

    /**
     * The `content://` URI the cache file [name] is handed out at as [kind], [PIPE] or [UNSIZED].
     */
    fun uriOf(
      kind: String,
      name: String,
    ): String = "content://$AUTHORITY/$kind/$name"
  }
}
