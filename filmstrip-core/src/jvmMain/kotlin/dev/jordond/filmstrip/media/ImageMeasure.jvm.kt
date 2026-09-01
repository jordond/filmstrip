package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/**
 * ImageIO picks a reader off the stream's own magic bytes and answers the bounds from the header,
 * so nothing here pulls the picture into memory. The readers the JDK ships do not surface the EXIF
 * orientation tag, so a photo stored sideways reports the bounds it was stored at.
 */
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement? =
  withContext(Dispatchers.IO) {
    val stream = image.openImageStream() ?: return@withContext null

    try {
      val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@withContext null
      try {
        reader.input = stream
        ImageMeasurement(
          size = Size(reader.getWidth(FIRST_IMAGE), reader.getHeight(FIRST_IMAGE)),
          exifOrientation = EXIF_ORIENTATION_NORMAL,
          format = reader.formatName.lowercase(),
        )
      } finally {
        reader.dispose()
      }
    } catch (unreadable: IOException) {
      null
    } finally {
      stream.close()
    }
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
