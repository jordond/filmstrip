package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The JDK writes PNG and JPEG and ships no WebP writer, so WebP is refused by name here rather
 * than sniffed for at runtime. JPEG quality needs a writer and an [ImageWriteParam]: the one-line
 * `ImageIO.write` takes the plugin's default and drops the setting silently.
 */
internal actual suspend fun PlatformImage.encode(
  spec: StillSpec,
  size: Size,
): StillBytes =
  withContext(Dispatchers.IO) {
    val source = asBufferedImage() ?: return@withContext StillBytes.Failure(closedFrame())

    when (spec.format) {
      StillFormat.Png -> {
        pngBytes(source.prepared(spec.format, size))?.let { StillBytes.Success(it, size, spec.format) }
          ?: StillBytes.Failure(ExportError.Underlying(NO_CODE, NO_PNG_WRITER))
      }
      StillFormat.Jpeg -> {
        jpegBytes(source.prepared(spec.format, size), spec.qualityFraction.toFloat())
          ?.let { StillBytes.Success(it, size, spec.format) }
          ?: StillBytes.Failure(ExportError.Underlying(NO_CODE, NO_JPEG_WRITER))
      }
      StillFormat.Webp -> {
        StillBytes.Failure(unsupportedStillFormat(spec.format, TARGET))
      }
    }
  }

internal actual suspend fun writeStill(
  bytes: ByteArray,
  to: MediaSink,
  format: StillFormat,
): StillWrite =
  withContext(Dispatchers.IO) {
    when (to) {
      is MediaSink.Path -> {
        writeStillFile(bytes, to.path, MediaSink.Path(to.path))
      }
      is MediaSink.Temporary -> {
        val path = temporaryStillPath(format)
        writeStillFile(bytes, path, MediaSink.Path(path))
      }
      is MediaSink.Uri -> {
        val path = filePathOf(to.uri)
        if (path == null) {
          StillWrite.Failure(ExportError.SinkUnwritable(to.uri, NOT_A_FILE_URL))
        } else {
          writeStillFile(bytes, path, MediaSink.Uri(to.uri))
        }
      }
    }
  }

/**
 * The image the writer is handed: at [size], and in a colour model the format can hold.
 *
 * JPEG carries no alpha channel, and a writer handed an image that has one produces colours the
 * caller never asked for, so the frame is drawn onto an opaque image first.
 */
private fun BufferedImage.prepared(
  format: StillFormat,
  size: Size,
): BufferedImage {
  val wanted = if (format == StillFormat.Jpeg) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
  if (width == size.width && height == size.height && type == wanted) return this

  val target = BufferedImage(size.width, size.height, wanted)
  val graphics = target.createGraphics()
  try {
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.drawImage(this, 0, 0, size.width, size.height, null)
  } finally {
    graphics.dispose()
  }
  return target
}

private fun pngBytes(image: BufferedImage): ByteArray? {
  val stream = ByteArrayOutputStream()
  return if (ImageIO.write(image, "png", stream)) stream.toByteArray() else null
}

private fun jpegBytes(
  image: BufferedImage,
  quality: Float,
): ByteArray? {
  val writer = ImageIO.getImageWritersByFormatName("jpeg").let { if (it.hasNext()) it.next() else null } ?: return null
  val bytes = ByteArrayOutputStream()

  return try {
    ImageIO.createImageOutputStream(bytes).use { output ->
      writer.output = output
      val params =
        writer.defaultWriteParam.apply {
          compressionMode = ImageWriteParam.MODE_EXPLICIT
          compressionQuality = quality
        }
      writer.write(null, IIOImage(image, null, null), params)
    }
    bytes.toByteArray()
  } finally {
    writer.dispose()
  }
}

/**
 * The path a `file:` URL names, or null for anything else. The JVM has no content resolver to hand
 * another scheme to.
 */
private fun filePathOf(uri: String): String? =
  try {
    val parsed = URI(uri)
    if (parsed.scheme == "file") parsed.path else null
  } catch (malformed: URISyntaxException) {
    null
  }

private fun closedFrame() = ExportError.SourceUnreadable("PlatformImage", "The frame has been closed.")

private const val TARGET = "The JDK's ImageIO"

private const val NOT_A_FILE_URL = "This target writes to file URLs only."

private const val NO_PNG_WRITER = "ImageIO found no PNG writer on this JDK."

private const val NO_JPEG_WRITER = "ImageIO found no JPEG writer on this JDK."

private const val NO_CODE = ExportError.Underlying.NO_PLATFORM_CODE
