package dev.jordond.filmstrip.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * `inJustDecodeBounds` reads the header and allocates no pixels, and `ExifInterface` reads the
 * orientation a phone camera writes rather than baking into the pixels. Both read from the head of
 * a stream, so the source is opened twice rather than buffered whole.
 */
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement? =
  withContext(Dispatchers.IO) {
    val bounds = image.open()?.use { it.readBounds() } ?: return@withContext null
    val orientation = image.open()?.use { it.readOrientation() } ?: EXIF_ORIENTATION_NORMAL

    ImageMeasurement(
      size = bounds.size,
      exifOrientation = orientation,
      format = bounds.mimeType.substringAfterLast('/'),
    )
  }

private fun InputStream.readBounds(): ImageBounds? {
  val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeStream(this, null, options)
  if (options.outWidth <= 0 || options.outHeight <= 0) return null

  return ImageBounds(
    size = Size(options.outWidth, options.outHeight),
    mimeType = options.outMimeType.orEmpty(),
  )
}

private fun InputStream.readOrientation(): Int =
  try {
    ExifInterface(this).getAttributeInt(ExifInterface.TAG_ORIENTATION, EXIF_ORIENTATION_NORMAL)
  } catch (unreadable: IOException) {
    // A format that carries no EXIF block reads as stored the way it is shown, which is what a PNG
    // and a WebP always are.
    EXIF_ORIENTATION_NORMAL
  }

private fun ImageSource.open(): InputStream? =
  try {
    when (this) {
      is ImageSource.Path -> File(path).takeIf { it.isFile }?.let(::FileInputStream)
      is ImageSource.Uri -> FilmstripContext.get()?.contentResolver?.openInputStream(Uri.parse(uri))
      is ImageSource.Bytes -> ByteArrayInputStream(bytes)
    }
  } catch (unreadable: IOException) {
    null
  } catch (unresolvable: SecurityException) {
    // A content:// URI the app has not been granted, which is a source it cannot read rather than
    // a crash.
    null
  }

/**
 * What `inJustDecodeBounds` filled in.
 *
 * @property size The stored pixel bounds.
 * @property mimeType What the decoder made of the header, such as `image/jpeg`.
 */
private class ImageBounds(
  val size: Size,
  val mimeType: String,
)
