package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.describe
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * The size this picture is shown at when nothing decoded it, which is the size the graph places it by.
 *
 * The JDK reads the size out of the picture's header. A path or file URL that is not a file this process can read is
 * handed to [refuse], as is a picture the JDK cannot size.
 */
internal inline fun ImageSource.shownSize(refuse: (String) -> Nothing): Size {
  val bytes = encodedBytes() ?: refuse(unreadableImageFile(this))
  return shownOf(bytes, measureOverlay(bytes) ?: refuse(UNREADABLE_IMAGE))
}

/**
 * The size a picture of [stored] texels encoded as [bytes] is shown at, cut to the crop [cropLeftOf] reads and turned
 * by the orientation the graph lays the picture out by.
 */
internal fun shownOf(
  bytes: ByteArray,
  stored: Size,
): Size = orientationLeftOf(bytes).orientedSize(cropLeftOf(bytes)?.size ?: stored)

/**
 * The stored pixel size of the picture [bytes] encode, or null when the JDK has no reader for them.
 *
 * ImageIO picks a reader off the bytes' own magic and answers the bounds out of the header, so no texel is decoded.
 */
internal fun measureOverlay(bytes: ByteArray): Size? {
  val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null

  return try {
    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
    reader?.let {
      try {
        it.input = stream
        Size(it.getWidth(FIRST_IMAGE), it.getHeight(FIRST_IMAGE))
      } finally {
        it.dispose()
      }
    }
  } catch (unreadable: IOException) {
    null
  } finally {
    stream.close()
  }
}

// The image in an ImageIO stream, which is the only one a still carries.
private const val FIRST_IMAGE = 0

internal const val UNREADABLE_IMAGE =
  "The overlay image could not be read. An overlay is sized and placed against the picture's own " +
    "pixels, so this backend reads its header, and neither the JDK nor this ffmpeg could read it. " +
    "Check that the path or URL is readable by this process, and that the bytes are a picture this " +
    "ffmpeg build decodes."

// Why an overlay is refused when [image] names no file this process can read, with the path or URL it names.
internal fun unreadableImageFile(image: ImageSource): String =
  "The overlay image ${image.describe()} could not be read. An overlay is sized and placed against the picture's " +
    "own pixels, so this backend reads its header, and this process found no file there to read. Check that the " +
    "path or URL names a file readable by this process."
