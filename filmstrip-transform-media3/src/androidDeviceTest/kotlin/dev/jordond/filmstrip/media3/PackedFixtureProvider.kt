package dev.jordond.filmstrip.media3

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hands out a test fixture as a range of a larger file, the way a resource packed into an APK is opened.
 *
 * The file opens on [PADDING] bytes that belong to no fixture, so a reader that takes the file descriptor as the whole
 * fixture reads them first and finds no container there. A fixture missing from the test APK's resources fails to open,
 * so a test checks for it first and skips.
 */
class PackedFixtureProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun openAssetFile(
    uri: Uri,
    mode: String,
  ): AssetFileDescriptor {
    val name = requireNotNull(uri.lastPathSegment) { "No fixture named in $uri" }
    val fixture = fixtureBytes(name)
    val packed = File(requireNotNull(context).cacheDir, "packed-$name")
    if (!packed.exists()) {
      // Written beside the file and renamed over it, so a run killed part way never leaves a truncated one behind.
      val partial = File.createTempFile("${packed.name}.", ".partial", packed.parentFile)
      try {
        partial.writeBytes(ByteArray(PADDING) + fixture)
        if (!partial.renameTo(packed)) throw IOException("could not move $partial over $packed")
      } finally {
        partial.delete()
      }
    }
    val descriptor = ParcelFileDescriptor.open(packed, ParcelFileDescriptor.MODE_READ_ONLY)
    return AssetFileDescriptor(descriptor, PADDING.toLong(), fixture.size.toLong())
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

  private fun fixtureBytes(name: String): ByteArray =
    javaClass.classLoader
      ?.getResourceAsStream(name)
      ?.use { it.readBytes() }
      ?: throw FileNotFoundException("the fixture $name is not among the test APK's resources")

  companion object {
    private const val AUTHORITY = "dev.jordond.filmstrip.media3.test.packed"
    private const val PADDING = 4096

    /**
     * The `content://` URI the fixture [name] is handed out at.
     */
    fun uriOf(name: String): String = "content://$AUTHORITY/$name"
  }
}
