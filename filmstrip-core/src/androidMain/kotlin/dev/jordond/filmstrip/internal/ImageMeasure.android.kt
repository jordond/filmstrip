package dev.jordond.filmstrip.internal

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.ALPHA_HEADER_BYTES
import dev.jordond.filmstrip.picture.imageHasAlphaOf
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
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement =
  withContext(Dispatchers.IO) {
    val bounds =
      when (val opened = image.open()) {
        is Opened.Refused -> return@withContext ImageMeasurement.Unreadable(opened.reason)
        is Opened.Stream -> opened.stream.use { it.readBounds() } ?: return@withContext ImageMeasurement.Unreadable()
      }
    val hasAlpha = (image.open() as? Opened.Stream)?.stream?.use { it.readHeader() }?.let(::imageHasAlphaOf) ?: false
    val orientation = (image.open() as? Opened.Stream)?.stream?.use { it.readOrientation() } ?: EXIF_ORIENTATION_NORMAL

    ImageMeasurement.Measured(
      size = bounds.size,
      exifOrientation = orientation,
      format = bounds.mimeType.substringAfterLast('/'),
      hasAlpha = hasAlpha,
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

private fun InputStream.readHeader(): ByteArray {
  val buffer = ByteArray(ALPHA_HEADER_BYTES)
  var filled = 0
  while (filled < buffer.size) {
    val read = read(buffer, filled, buffer.size - filled)
    if (read <= 0) break
    filled += read
  }

  return buffer.copyOf(filled)
}

private fun InputStream.readOrientation(): Int =
  try {
    ExifInterface(this).getAttributeInt(ExifInterface.TAG_ORIENTATION, EXIF_ORIENTATION_NORMAL)
  } catch (unreadable: IOException) {
    // A format that carries no EXIF block reads as stored the way it is shown, which is what a PNG
    // and a WebP always are.
    EXIF_ORIENTATION_NORMAL
  }

private fun ImageSource.open(): Opened {
  val stream =
    try {
      when (this) {
        is ImageSource.Path -> {
          File(path).takeIf { it.isFile }?.let(::FileInputStream)
        }
        is ImageSource.Uri -> {
          val context = FilmstripContext.get() ?: return Opened.Refused(FilmstripContext.MISSING_CONTEXT)
          context.contentResolver.openInputStream(Uri.parse(uri))
        }
        is ImageSource.Bytes -> {
          ByteArrayInputStream(bytes)
        }
      }
    } catch (unreadable: IOException) {
      return Opened.Refused(unreadable.message ?: UNREADABLE)
    } catch (denied: SecurityException) {
      // A content:// URI the app has not been granted, which is a source it cannot read rather than
      // a crash.
      return Opened.Refused(denied.message ?: READ_DENIED)
    }

  return if (stream == null) Opened.Refused(UNREADABLE) else Opened.Stream(stream)
}

/**
 * The stream an image source opened to, or what stopped it opening.
 */
private sealed interface Opened {
  class Stream(
    val stream: InputStream,
  ) : Opened

  class Refused(
    val reason: String,
  ) : Opened
}

private const val READ_DENIED = "Reading the image was denied."

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
