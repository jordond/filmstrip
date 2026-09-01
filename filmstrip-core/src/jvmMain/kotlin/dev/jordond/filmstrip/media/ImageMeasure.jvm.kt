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
 * ImageIO picks a reader off the stream's own magic bytes and answers the bounds from the header, so
 * nothing here pulls the picture into memory. The readers the JDK ships do not surface the EXIF
 * orientation tag, so the head of the same stream is read back through [exifOrientationOf] for it.
 */
internal actual suspend fun measureImage(image: ImageSource): ImageMeasurement? =
  withContext(Dispatchers.IO) {
    val stream = image.openImageStream() ?: return@withContext null

    try {
      val header = stream.readExifHeader()
      stream.seek(0)

      val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@withContext null
      try {
        reader.input = stream
        ImageMeasurement(
          size = Size(reader.getWidth(FIRST_IMAGE), reader.getHeight(FIRST_IMAGE)),
          exifOrientation = exifOrientationOf(header),
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

/**
 * The head of the stream, as much of it as an EXIF block could sit in, leaving the position wherever
 * the read stopped.
 *
 * Bounded, because an EXIF block sits ahead of the pixels and a photo is a great deal larger than
 * one. A format that carries no block at all stops at the bytes saying so, which is what keeps a
 * library of PNGs from being read a bound's worth at a time for a tag none of them can hold. A
 * stream shorter than what is asked for answers with what it has.
 */
private fun ImageInputStream.readExifHeader(): ByteArray {
  val opening = readUpTo(EXIF_MAGIC_BYTES)
  if (!carriesExif(opening)) return opening

  return opening + readUpTo(IMAGE_HEADER_BYTES - opening.size)
}

/**
 * Up to [count] bytes from wherever the stream is, or fewer when it ends first.
 */
private fun ImageInputStream.readUpTo(count: Int): ByteArray {
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
