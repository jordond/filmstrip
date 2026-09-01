package dev.jordond.filmstrip.media

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Android encodes all three formats. WebP is the only one that moves with the OS version, because
 * `WEBP_LOSSY` arrives in API 30 and the module's floor is lower than that.
 */
internal actual suspend fun PlatformImage.encode(
  spec: StillSpec,
  size: Size,
): StillBytes =
  withContext(Dispatchers.IO) {
    val source = asBitmap() ?: return@withContext StillBytes.Failure(closedFrame())
    val scaled = source.scaledTo(size)
    val stream = ByteArrayOutputStream()

    try {
      val written = scaled.compress(compressFormatOf(spec.format, Build.VERSION.SDK_INT), spec.qualityPercent, stream)
      if (written) {
        StillBytes.Success(stream.toByteArray(), size, spec.format)
      } else {
        StillBytes.Failure(ExportError.Underlying(NO_CODE, "Bitmap.compress refused to write ${spec.format}."))
      }
    } finally {
      if (scaled !== source) scaled.recycle()
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
        // The app's cache directory is where a temporary still belongs. Without an installed
        // context there is no cache directory to name, so the system one answers instead.
        val cache = FilmstripContext.get()?.cacheDir
        val path = if (cache == null) temporaryStillPath(format) else File(cache, temporaryStillName(format)).path
        writeStillFile(bytes, path, MediaSink.Path(path))
      }
      is MediaSink.Uri -> {
        writeUri(bytes, to.uri)
      }
    }
  }

/**
 * The compress format for [format] on an OS at [sdkInt].
 *
 * `WEBP_LOSSY` and `WEBP_LOSSLESS` split what one `WEBP` value used to mean, and only from API 30.
 * Below that the deprecated value is the only one there is, and it reads [StillSpec.quality] the
 * same way `WEBP_LOSSY` does.
 */
@Suppress("DEPRECATION", "NewApi")
internal fun compressFormatOf(
  format: StillFormat,
  sdkInt: Int,
): Bitmap.CompressFormat =
  when (format) {
    StillFormat.Png -> {
      Bitmap.CompressFormat.PNG
    }
    StillFormat.Jpeg -> {
      Bitmap.CompressFormat.JPEG
    }
    StillFormat.Webp -> {
      if (sdkInt >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
  }

private fun Bitmap.scaledTo(size: Size): Bitmap =
  if (width == size.width && height == size.height) {
    this
  } else {
    Bitmap.createScaledBitmap(this, size.width, size.height, true)
  }

/**
 * Streams the still out through the content resolver, which is the only thing that can write a
 * `content://` destination. A `file:` URI is a path with a scheme on it and goes the direct way.
 */
private fun writeUri(
  bytes: ByteArray,
  uri: String,
): StillWrite {
  val parsed = runCatching { Uri.parse(uri) }.getOrNull()
  val scheme = parsed?.scheme

  if (parsed == null) return StillWrite.Failure(ExportError.SinkUnwritable(uri, NOT_A_URI))
  if (scheme == null || scheme == "file") {
    val path = parsed.path ?: return StillWrite.Failure(ExportError.SinkUnwritable(uri, NO_PATH))
    return writeStillFile(bytes, path, MediaSink.Uri(uri))
  }

  val resolver =
    FilmstripContext.get()?.contentResolver
      ?: return StillWrite.Failure(ExportError.SinkUnwritable(uri, FilmstripContext.MISSING_CONTEXT))

  return try {
    val stream = resolver.openOutputStream(parsed)
    if (stream == null) {
      StillWrite.Failure(ExportError.SinkUnwritable(uri, NO_STREAM))
    } else {
      stream.use { it.write(bytes) }
      StillWrite.Success(MediaSink.Uri(uri))
    }
  } catch (failure: IOException) {
    StillWrite.Failure(ExportError.SinkUnwritable(uri, failure.message ?: UNWRITABLE))
  } catch (denied: SecurityException) {
    StillWrite.Failure(ExportError.SinkUnwritable(uri, denied.message ?: DENIED))
  }
}

private const val NOT_A_URI = "The destination is not a valid URI."

private const val NO_PATH = "The file URI names no path."

private const val NO_STREAM = "The content provider gave back no stream to write to."

private const val UNWRITABLE = "The destination could not be written."

private const val DENIED = "Writing the destination was denied."
