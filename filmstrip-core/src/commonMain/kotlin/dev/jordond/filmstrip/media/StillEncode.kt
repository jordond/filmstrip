package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.MAX_STILL_FRAME
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.frameWithin
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.math.roundToInt
import kotlin.random.Random

// The numbers every target has to agree on when it encodes a still, worked out once here so a
// caller gets the same pixels and the same amount of loss whichever one answered.

/**
 * The size a still is encoded at.
 *
 * A [heightPx] that is null or not positive keeps [source] as it is. Any other value scales
 * [source] to that height, keeping its aspect and never rounding a side below one pixel. Whatever
 * that leaves is held to [MAX_STILL_FRAME] before it is returned, so a [source] already past that
 * ceiling is brought inside it too, not just one this function itself scaled up.
 */
internal fun stillSizeOf(
  source: Size,
  heightPx: Int?,
): Size {
  val target =
    if (heightPx == null || heightPx <= 0 || source.width <= 0 || source.height <= 0 || heightPx == source.height) {
      source
    } else {
      val width = (source.width.toDouble() * heightPx / source.height).roundToInt()
      Size(width.coerceAtLeast(1), heightPx)
    }
  return frameWithin(target, MAX_STILL_FRAME)
}

/**
 * [StillSpec.quality] as a percentage inside the range the property documents.
 */
internal val StillSpec.qualityPercent: Int
  get() = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

/**
 * [qualityPercent] as the zero-to-one fraction Apple, the JDK and a browser all take.
 */
internal val StillSpec.qualityFraction: Double
  get() = qualityPercent / MAX_QUALITY.toDouble()

/**
 * The media type of an encoded still, which a browser encodes by name and a content resolver is
 * told before it opens a stream.
 */
internal val StillFormat.mimeType: String
  get() =
    when (this) {
      StillFormat.Png -> "image/png"
      StillFormat.Jpeg -> "image/jpeg"
      StillFormat.Webp -> "image/webp"
    }

/**
 * The extension a generated filename gets, for a sink that names no file of its own.
 */
internal val StillFormat.fileExtension: String
  get() =
    when (this) {
      StillFormat.Png -> "png"
      StillFormat.Jpeg -> "jpg"
      StillFormat.Webp -> "webp"
    }

/**
 * The refusal [target] answers with for a format it cannot write.
 */
internal fun unsupportedStillFormat(
  format: StillFormat,
  target: String,
): ExportError.UnsupportedStillFormat =
  ExportError.UnsupportedStillFormat(
    format = format,
    message =
      "$target cannot write $format. Ask for ${StillFormat.Png} or ${StillFormat.Jpeg} instead, " +
        "which every target writes.",
  )

/**
 * Encodes the frame's pixels at [size] in [spec]'s format.
 *
 * [size] is passed in rather than worked out here, so no target scales a still differently from
 * the one beside it.
 */
internal expect suspend fun PlatformImage.encode(
  spec: StillSpec,
  size: Size,
): StillBytes

/**
 * Puts [bytes] where [to] asks for them, and reports where they ended up.
 */
internal expect suspend fun writeStill(
  bytes: ByteArray,
  to: MediaSink,
  format: StillFormat,
): StillWrite

/**
 * Where a still was delivered, or why it was not.
 */
internal sealed interface StillWrite {
  class Success(
    val output: MediaSink,
  ) : StillWrite

  class Failure(
    val error: ExportError,
  ) : StillWrite
}

/**
 * Writes [bytes] to [path] and reports [reported] for it.
 *
 * Shared by the three targets that have a filesystem. A browser has none and its [writeStill]
 * never calls this. A write that does not finish takes the file with it, so a half-written still
 * never outlives the call that failed to produce it.
 */
internal fun writeStillFile(
  bytes: ByteArray,
  path: String,
  reported: MediaSink,
): StillWrite {
  val target = Path(path)
  val parent = target.parent
  if (parent != null && !SystemFileSystem.exists(parent)) {
    return StillWrite.Failure(ExportError.SinkUnwritable(path, NO_PARENT))
  }

  return try {
    SystemFileSystem.sink(target).buffered().use { it.write(bytes) }
    StillWrite.Success(reported)
  } catch (failure: IOException) {
    SystemFileSystem.delete(target, mustExist = false)
    StillWrite.Failure(ExportError.SinkUnwritable(path, failure.message ?: UNWRITABLE))
  }
}

/**
 * The refusal a target answers with for a frame that was closed before it could be encoded.
 */
internal fun closedFrame(): ExportError = ExportError.SourceUnreadable("PlatformImage", "The frame has been closed.")

/**
 * What a target that writes through the filesystem says about a URI that names no file.
 */
internal const val NOT_A_FILE_URL: String = "This target writes to file URLs only."

/**
 * The platform code an error carries when the platform reported none.
 */
internal const val NO_CODE: Int = ExportError.Underlying.NO_PLATFORM_CODE

/**
 * A path in the system temporary directory for a still a caller asked to have put somewhere it
 * does not name.
 */
internal fun temporaryStillPath(format: StillFormat): String =
  Path(SystemTemporaryDirectory, "filmstrip-still-${randomToken()}.${format.fileExtension}").toString()

/**
 * A filename for a still a caller asked to have put somewhere that names no file.
 */
internal fun temporaryStillName(format: StillFormat): String =
  "filmstrip-still-${randomToken()}.${format.fileExtension}"

private fun randomToken(): String = Random.nextLong(0, Long.MAX_VALUE).toString(RADIX)

private const val RADIX = 36

private const val MIN_QUALITY = 0

private const val MAX_QUALITY = 100

private const val NO_PARENT = "The destination's parent directory does not exist."

private const val UNWRITABLE = "The destination could not be written."
