package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.picture.ALPHA_HEADER_BYTES
import dev.jordond.filmstrip.picture.exifOrientationOf
import dev.jordond.filmstrip.picture.imageHasAlphaOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/**
 * ImageIO picks a reader off the stream's own magic bytes and answers the bounds from the header, so nothing here pulls
 * the picture into memory. The readers the JDK ships do not surface an orientation, so it is read through
 * [exifOrientationOf] first, straight out of the array for bytes and from the ranges of a file that hold the tag.
 */
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement =
  withContext(Dispatchers.IO) {
    val stream = image.openImageStream() ?: return@withContext ImageMeasurement.Unreadable()

    try {
      val orientation =
        when (image) {
          is ImageSource.Bytes -> exifOrientationOf(image.bytes)
          else -> exifOrientationOf(stream.length()) { start, count -> stream.readAt(start, count) }
        }
      val hasAlpha = imageHasAlphaOf(stream.readAt(0, ALPHA_HEADER_BYTES))
      stream.seek(0)

      val reader =
        ImageIO.getImageReaders(stream).asSequence().firstOrNull()
          ?: return@withContext ImageMeasurement.Unreadable()
      try {
        reader.input = stream
        ImageMeasurement.Measured(
          size = Size(reader.getWidth(FIRST_IMAGE), reader.getHeight(FIRST_IMAGE)),
          exifOrientation = orientation,
          format = reader.formatName.lowercase(),
          hasAlpha = hasAlpha,
        )
      } finally {
        reader.dispose()
      }
    } catch (unreadable: IOException) {
      ImageMeasurement.Unreadable()
    } finally {
      stream.close()
    }
  }

/**
 * Up to [count] bytes from [start], or fewer when the stream ends first.
 */
private fun ImageInputStream.readAt(
  start: Long,
  count: Int,
): ByteArray {
  seek(start)
  val buffer = ByteArray(count)
  var filled = 0
  while (filled < buffer.size) {
    val read = read(buffer, filled, buffer.size - filled)
    if (read <= 0) break
    filled += read
  }

  return buffer.copyOf(filled)
}

private fun ImageSource.openImageStream(): ImageInputStream? =
  try {
    when (this) {
      is ImageSource.Path -> {
        File(path).takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
      }
      is ImageSource.Uri -> {
        val named = filePathOf(uri)?.let(::File)
        named?.takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
      }
      is ImageSource.Bytes -> {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
      }
    }
  } catch (unreadable: IOException) {
    null
  }

private const val FIRST_IMAGE = 0
