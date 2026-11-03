package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.net.Uri
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.sinkParentRefusal
import dev.jordond.filmstrip.media.writeContentUri
import java.io.File
import java.io.IOException

/**
 * Where media3 writes, and what the caller is told afterwards.
 *
 * `Transformer.start` takes a path and nothing else, so a destination that is not a path is written
 * to the cache first and streamed out in [publish]. The caller never sees the intermediate file.
 *
 * @property path The file media3 writes to.
 */
internal class Media3Destination(
  val path: String,
  private val context: Context,
  private val target: Uri?,
  private val reported: MediaSink,
) {
  /**
   * Moves the written file where the caller asked for it.
   *
   * @return The sink to report, or the error that stopped it getting there.
   */
  fun publish(): PublishResult {
    val destination = target ?: return PublishResult.Success(reported)

    return try {
      val refused =
        context.contentResolver.writeContentUri(destination) { out -> File(path).inputStream().use { it.copyTo(out) } }
      if (refused == null) PublishResult.Success(reported) else PublishResult.Failure(refused)
    } finally {
      discard()
    }
  }

  /**
   * Removes the file media3 was writing.
   *
   * Everything at [path] belongs to the run: media3 either created the file or truncated whatever
   * was there when it opened it. A destination the caller named is no exception, so a run that did
   * not finish leaves nothing behind rather than a half-written video where a whole one was.
   */
  fun discard() {
    File(path).delete()
  }

  /**
   * The outcome of moving a written file to where it was asked for.
   */
  sealed interface PublishResult {
    class Success(
      val sink: MediaSink,
    ) : PublishResult

    class Failure(
      val error: FilmstripError,
    ) : PublishResult
  }
}

/**
 * Where an export should write, or why this sink cannot be written to.
 */
internal sealed interface DestinationResult {
  class Ready(
    val destination: Media3Destination,
  ) : DestinationResult

  class Failed(
    val error: FilmstripError,
  ) : DestinationResult
}

/**
 * Works out where to write for [sink].
 *
 * A path is written to directly. Anything else goes through the cache, because media3 writes to
 * paths and nothing else.
 */
internal fun resolveDestination(
  context: Context,
  sink: MediaSink,
): DestinationResult =
  when (sink) {
    is MediaSink.Path -> {
      context.forFile(sink.path, MediaSink.Path(sink.path))
    }
    is MediaSink.Temporary -> {
      context.forScratch(target = null, reported = null)
    }
    is MediaSink.Uri -> {
      context.forUri(sink.uri)
    }
  }

private fun Context.forUri(uri: String): DestinationResult {
  val parsed = runCatching { Uri.parse(uri) }.getOrNull()
  val scheme = parsed?.scheme

  return when {
    parsed == null -> {
      DestinationResult.Failed(
        FilmstripError.SinkUnwritable(uri, "The destination is not a valid URI."),
      )
    }
    scheme != null && scheme != "file" -> {
      forScratch(target = parsed, reported = MediaSink.Uri(uri))
    }
    parsed.path == null -> {
      DestinationResult.Failed(FilmstripError.SinkUnwritable(uri, "The file URI names no path."))
    }
    else -> {
      forFile(parsed.path.orEmpty(), MediaSink.Path(parsed.path.orEmpty()))
    }
  }
}

private fun Context.forFile(
  path: String,
  reported: MediaSink,
): DestinationResult =
  sinkParentRefusal(path)?.let { DestinationResult.Failed(it) }
    ?: DestinationResult.Ready(Media3Destination(path, this, target = null, reported = reported))

private fun Context.forScratch(
  target: Uri?,
  reported: MediaSink?,
): DestinationResult =
  try {
    val file = File.createTempFile("filmstrip-", ".mp4", cacheDir)
    DestinationResult.Ready(Media3Destination(file.path, this, target, reported ?: MediaSink.Path(file.path)))
  } catch (failure: IOException) {
    DestinationResult.Failed(
      FilmstripError.SinkUnwritable(
        cacheDir.path,
        failure.message ?: "A temporary file could not be created in the cache directory.",
      ),
    )
  }
