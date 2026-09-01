package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size

/**
 * Reads [image]'s header, or returns null when this target cannot open it.
 *
 * Bounds and orientation only. Nothing here decodes the picture.
 */
internal expect suspend fun measureImage(image: ImageSource): ImageMeasurement?

/**
 * What a still's header says about it.
 *
 * @property size The stored pixel bounds.
 * @property exifOrientation The EXIF orientation tag, or [EXIF_ORIENTATION_NORMAL] when the header
 *   carries none or the read already resolved it.
 * @property format The platform's own spelling of the format, or empty when it named none.
 */
internal class ImageMeasurement(
  val size: Size,
  val exifOrientation: Int,
  val format: String,
)

/**
 * Answers a probe for an image source out of the image's own header.
 *
 * A still carries no container, so this needs no demuxer and no toolchain. That is what lets every
 * target report an image clip, including the two whose probers decline everything else.
 */
internal suspend fun probeImage(source: MediaSource.Image): ProbeResult {
  val measured =
    measureImage(source.image)
      ?: return ProbeResult.Failure(ExportError.SourceUnreadable(source.describe(), UNREADABLE))

  return ProbeResult.Success(
    imageMediaInfoOf(
      codedSize = measured.size,
      exifOrientation = measured.exifOrientation,
      format = measured.format,
      duration = source.duration,
    ),
  )
}

private const val UNREADABLE =
  "The image could not be opened. It is either missing or in a format this target does not decode."
