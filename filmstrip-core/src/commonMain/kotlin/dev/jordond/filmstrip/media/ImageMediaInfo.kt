package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

// What a still image reports when something asks what it is, worked out once here. A target's
// header read only has to answer the two questions a still can answer, how big it is and which way
// up it was stored, and every other field is settled in one place for all four of them.

/**
 * The EXIF orientation of an image stored the same way up as it is shown.
 */
@InternalFilmstripApi
public const val EXIF_ORIENTATION_NORMAL: Int = 1

/**
 * What a still image is, as a [MediaInfo] anything that reads a source can take.
 *
 * The track is a video track, because a video track is what the still contributes to the output.
 * Its codec kind is [CodecKind.Other], which is what refuses a copy of pixels no muxer can carry
 * and so puts an image clip on the transcode path.
 *
 * @param codedSize The stored pixel bounds, before [exifOrientation] is applied.
 * @param exifOrientation The EXIF orientation tag, or [EXIF_ORIENTATION_NORMAL] when the image
 *   carries none and when the platform already resolved it during the read.
 * @param format The platform's own spelling of the still format, such as `jpeg`, or empty when it
 *   named none.
 * @param duration How long the still is held.
 */
@InternalFilmstripApi
public fun imageMediaInfoOf(
  codedSize: Size,
  exifOrientation: Int,
  format: String,
  duration: Duration,
): MediaInfo {
  val rotation = imageRotationOf(exifOrientation)

  return MediaInfo(
    duration = duration,
    video =
      VideoTrackInfo(
        codedSize = codedSize,
        displaySize = displaySizeOf(codedSize, rotation, SQUARE),
        rotationDegrees = rotation,
        // No still format any of the header reads opens stores a non-square pixel.
        pixelAspectRatio = SQUARE,
        // A still has no cadence of its own, so the output takes its rate from the clips that do
        // and from the export spec, rather than from a number invented here.
        frameRate = null,
        codec = TrackCodec(name = format, kind = CodecKind.Other),
        // A header read reports the depth for some formats and not others, and the field is
        // already documented as often absent, so no target claims one.
        bitDepth = null,
        // The enum carries no sRGB arm. sRGB shares its primaries with BT.709 and differs in
        // transfer, so BT.709 is the closest true statement about a still.
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        bitrate = null,
      ),
    audio = null,
    // Nothing about a still is protected. A format the decoder will not read fails at the decode.
    isExportable = true,
  )
}

/**
 * The quarter turn a player applies to an image stored with [exifOrientation].
 *
 * EXIF numbers eight orientations, four of which mirror the image as well as turning it. Filmstrip
 * carries no mirror, so a mirrored orientation reports the turn it shares with its unmirrored twin.
 * A value outside the range reads as no rotation.
 */
@InternalFilmstripApi
public fun imageRotationOf(exifOrientation: Int): Int =
  when (exifOrientation) {
    EXIF_ORIENTATION_NORMAL, ORIENTATION_FLIP_HORIZONTAL -> 0
    ORIENTATION_ROTATE_180, ORIENTATION_FLIP_VERTICAL -> HALF_TURN
    ORIENTATION_TRANSPOSE, ORIENTATION_ROTATE_90 -> QUARTER_TURN
    ORIENTATION_TRANSVERSE, ORIENTATION_ROTATE_270 -> THREE_QUARTER_TURN
    else -> 0
  }

private const val ORIENTATION_FLIP_HORIZONTAL = 2
private const val ORIENTATION_ROTATE_180 = 3
private const val ORIENTATION_FLIP_VERTICAL = 4
private const val ORIENTATION_TRANSPOSE = 5
private const val ORIENTATION_ROTATE_90 = 6
private const val ORIENTATION_TRANSVERSE = 7
private const val ORIENTATION_ROTATE_270 = 8

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270

private const val SQUARE = 1f
