package dev.jordond.filmstrip.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.jordond.filmstrip.test.PrimedAudio
import java.io.File
import kotlin.concurrent.thread

/**
 * Hands out [PrimedAudio.mp4] as a `content://` URI, either as a file the reader can seek through at [SEEKABLE] or
 * written down a pipe at [PIPED].
 */
class PrimedAudioProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun openFile(
    uri: Uri,
    mode: String,
  ): ParcelFileDescriptor =
    when (uri.lastPathSegment) {
      SEEKABLE_PATH -> ParcelFileDescriptor.open(primedFile(), ParcelFileDescriptor.MODE_READ_ONLY)
      PIPED_PATH -> piped()
      else -> throw IllegalArgumentException("No primed file at $uri")
    }

  override fun getType(uri: Uri): String = "audio/mp4"

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

  private fun primedFile(): File {
    val file = File(requireNotNull(context).cacheDir, "primed-aac-provided.mp4")
    if (!file.exists()) file.writeBytes(PrimedAudio.mp4())
    return file
  }

  private fun piped(): ParcelFileDescriptor {
    val (reader, writer) = ParcelFileDescriptor.createPipe()
    thread {
      // A reader that gives up early closes its end, which fails the write, and that is fine here.
      runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writer).use { it.write(PrimedAudio.mp4()) } }
    }
    return reader
  }

  companion object {
    private const val AUTHORITY = "dev.jordond.filmstrip.core.test.primed"
    private const val SEEKABLE_PATH = "seekable"
    private const val PIPED_PATH = "piped"

    /**
     * The primed file as a file descriptor that can seek.
     */
    val SEEKABLE: String = "content://$AUTHORITY/$SEEKABLE_PATH"

    /**
     * The primed file as the reading end of a pipe, which cannot seek and has no size.
     */
    val PIPED: String = "content://$AUTHORITY/$PIPED_PATH"
  }
}
