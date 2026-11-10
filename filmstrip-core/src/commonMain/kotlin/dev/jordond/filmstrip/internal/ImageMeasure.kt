package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.imageMediaInfoOf

/**
 * Reads [image]'s header, or says why this target could not open it.
 *
 * Bounds and orientation only. Nothing here decodes the picture.
 */
internal expect suspend fun measureImage(image: ImageSource): ImageMeasurement

/**
 * What a still's header says about it, or why it could not be read.
 */
internal sealed interface ImageMeasurement {
  /**
   * The header was read.
   *
   * @property size The stored pixel bounds.
   * @property exifOrientation The EXIF orientation tag, or [EXIF_ORIENTATION_NORMAL] when the header
   *   carries none or the read already resolved it.
   * @property format The platform's own spelling of the format, or empty when it named none.
   * @property hasAlpha Whether the still carries transparent pixels.
   */
  class Measured(
    val size: Size,
    val exifOrientation: Int,
    val format: String,
    val hasAlpha: Boolean,
  ) : ImageMeasurement

  /**
   * The image could not be opened or its header could not be read.
   *
   * @property reason What a caller is told, which is [UNREADABLE] unless the platform named
   *   something more specific, like a refused grant.
   */
  class Unreadable(
    val reason: String = UNREADABLE,
  ) : ImageMeasurement
}

/**
 * Answers a probe for an image source out of the image's own header.
 *
 * A still carries no container, so this needs no demuxer and no toolchain. That is what lets every
 * target report an image clip, including the two whose probers decline everything else.
 */
internal suspend fun probeImage(source: MediaSource.Image): ProbeResult {
  val measured =
    when (val measurement = measureImage(source.image)) {
      is ImageMeasurement.Measured -> {
        measurement
      }
      is ImageMeasurement.Unreadable -> {
        return ProbeResult.Failure(FilmstripError.SourceUnreadable(source.describe(), measurement.reason))
      }
    }

  return ProbeResult.Success(
    imageMediaInfoOf(
      codedSize = measured.size,
      exifOrientation = measured.exifOrientation,
      format = measured.format,
      duration = source.duration,
      hasAlpha = measured.hasAlpha,
    ),
  )
}

/**
 * What a probe reports for an image that is missing or whose header this target does not decode.
 */
internal const val UNREADABLE =
  "The image could not be opened. It is either missing or in a format this target does not decode."
