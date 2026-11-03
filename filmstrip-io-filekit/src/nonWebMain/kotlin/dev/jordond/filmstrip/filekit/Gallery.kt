package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.saveImageToGallery
import io.github.vinceglb.filekit.saveVideoToGallery

/**
 * Saves what an export wrote into the platform's video gallery.
 *
 * Android inserts it into `MediaStore`, Apple adds it to the photo library and the JVM copies it
 * into the user's videos directory. An iOS app declares `NSPhotoLibraryAddUsageDescription` in its
 * plist, and an Android app that still runs below API 29 declares `WRITE_EXTERNAL_STORAGE` with
 * `maxSdkVersion="28"`, since FileKit's own artifact declares neither. Nothing is needed on API 29
 * and up.
 *
 * @param output Where the export wrote, taken whole so a `content://` destination stays one.
 * @param filename The name the gallery entry takes, defaulting to the written file's own name.
 * @return [Result.failure] when the save does not go through. It carries an [IllegalStateException] for
 *   [MediaSink.Temporary], which is a request rather than a location, so read the resolved path off
 *   `ExportStatus.Success.output` instead. A [MediaSink.Uri] this platform cannot parse carries an
 *   [IllegalArgumentException], and anything else is FileKit's own exception.
 */
public suspend fun FileKit.saveVideoToGallery(
  output: MediaSink,
  filename: String? = null,
): Result<Unit> {
  val file = output.toGalleryFile().getOrElse { return Result.failure(it) }

  return saveVideoToGallery(file = file, filename = filename ?: file.name)
}

/**
 * Saves a still into the platform's image gallery.
 *
 * Android inserts it into `MediaStore`, Apple adds it to the photo library and the JVM copies it
 * into the user's pictures directory. The same plist key and the same pre-29 Android permission as
 * the video overload apply.
 *
 * @param output Where the still was written, taken whole so a `content://` destination stays one.
 * @param filename The name the gallery entry takes, defaulting to the written file's own name.
 * @return [Result.failure] when the save does not go through. It carries an [IllegalStateException] for
 *   [MediaSink.Temporary], which is a request rather than a location, so read the resolved path off
 *   `ExportStatus.Success.output` instead. A [MediaSink.Uri] this platform cannot parse carries an
 *   [IllegalArgumentException], and anything else is FileKit's own exception.
 */
public suspend fun FileKit.saveImageToGallery(
  output: MediaSink,
  filename: String? = null,
): Result<Unit> {
  val file = output.toGalleryFile().getOrElse { return Result.failure(it) }

  return saveImageToGallery(file = file, filename = filename ?: file.name)
}

private fun MediaSink.toGalleryFile(): Result<PlatformFile> =
  try {
    Result.success(toPlatformFile())
  } catch (unparseable: IllegalArgumentException) {
    Result.failure(unparseable)
  } catch (temporary: IllegalStateException) {
    Result.failure(temporary)
  }
