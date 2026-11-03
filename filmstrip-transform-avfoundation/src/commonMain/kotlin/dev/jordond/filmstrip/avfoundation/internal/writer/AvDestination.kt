package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.sinkParentRefusal
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * Where the writer writes, and what the caller is told afterwards.
 *
 * `AVAssetWriter` takes a file URL and refuses to start when something is already there, so every
 * path is cleared before writing and removed again if the run does not finish. Any destination
 * filmstrip can write at all is a file URL it can hand straight to the writer.
 *
 * @property url The file URL the writer opens.
 * @property path The same location as a filesystem path, for probing the result back.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvDestination(
  val url: NSURL,
  val path: String,
  private val reported: MediaSink,
) {
  /**
   * The sink to report on success. A [MediaSink.Temporary] request resolves to the real path here.
   */
  val sink: MediaSink get() = reported

  /**
   * The written file, as something the prober can read back.
   */
  fun asSource(): MediaSource = MediaSource.Path(path)

  /**
   * Removes whatever is at [path].
   *
   * Called before writing and again if the run does not finish. Everything at [path] belongs to the
   * run once the writer has opened it, a destination the caller named included, so an abandoned run
   * leaves nothing behind instead of a half-written video.
   */
  fun discard() {
    NSFileManager.defaultManager.removeItemAtURL(url, error = null)
  }
}

/**
 * Where an export should write, or why this sink cannot be written to.
 */
internal sealed interface DestinationResult {
  class Ready(
    val destination: AvDestination,
  ) : DestinationResult

  class Failed(
    val error: FilmstripError,
  ) : DestinationResult
}

/**
 * Works out where to write for [sink].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun resolveDestination(sink: MediaSink): DestinationResult =
  when (sink) {
    is MediaSink.Path -> {
      forFile(sink.path, MediaSink.Path(sink.path))
    }
    is MediaSink.Temporary -> {
      val path = NSTemporaryDirectory() + "filmstrip-" + NSUUID().UUIDString() + ".mp4"
      DestinationResult.Ready(AvDestination(NSURL.fileURLWithPath(path), path, MediaSink.Path(path)))
    }
    is MediaSink.Uri -> {
      forUri(sink.uri)
    }
  }

@OptIn(ExperimentalForeignApi::class)
private fun forUri(uri: String): DestinationResult {
  val parsed =
    NSURL.URLWithString(uri)
      ?: return DestinationResult.Failed(FilmstripError.SinkUnwritable(uri, NOT_A_URL))

  if (!parsed.isFileURL()) {
    return DestinationResult.Failed(FilmstripError.SinkUnwritable(uri, NOT_A_FILE_URL))
  }

  val path = parsed.path ?: return DestinationResult.Failed(FilmstripError.SinkUnwritable(uri, NO_PATH))
  return forFile(path, MediaSink.Uri(uri))
}

// NSURL reads a relative path against the working directory, which is where the shared check reads it too.
@OptIn(ExperimentalForeignApi::class)
private fun forFile(
  path: String,
  reported: MediaSink,
): DestinationResult =
  sinkParentRefusal(path)?.let { DestinationResult.Failed(it) }
    ?: DestinationResult.Ready(AvDestination(NSURL.fileURLWithPath(path), path, reported))

private const val NOT_A_URL = "The destination is not a valid URL."

private const val NOT_A_FILE_URL =
  "This backend writes to file URLs only. Copy the result out of a MediaSink.Temporary export to " +
    "reach a photo library or an iCloud container."

private const val NO_PATH = "The file URL names no path."
