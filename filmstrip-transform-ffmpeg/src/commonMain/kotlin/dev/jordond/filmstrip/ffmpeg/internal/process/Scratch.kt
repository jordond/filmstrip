package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.filePathOf
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * One directory per export, for the things ffmpeg can only read from a file.
 *
 * Deleted on completion, on failure and on cancellation. The output is not in here:
 * `MediaSink.Temporary` belongs to the caller once the export finishes, and filmstrip never deletes
 * it.
 */
internal class Scratch private constructor(
  private val directory: Path,
) {
  private var counter = 0
  private val sidecars = mutableMapOf<String, String>()

  /**
   * Writes one sidecar's bytes and returns the path the graph reads them from.
   *
   * Keyed by the placeholder, which is a digest of what the file holds, so a graph that reads the
   * same table from two nodes writes it once.
   */
  fun write(sidecar: Sidecar): String =
    sidecars.getOrPut(sidecar.placeholder) { write(sidecar.bytes, sidecar.extension) }

  fun materialise(image: ImageSource): String =
    when (image) {
      is ImageSource.Path -> image.path
      is ImageSource.Uri -> filePathOf(image.uri) ?: image.uri
      is ImageSource.Bytes -> write(image.bytes, "png")
    }

  fun write(
    bytes: ByteArray,
    extension: String,
  ): String {
    val target = Path(directory, "asset${counter++}.$extension")
    SystemFileSystem.sink(target).buffered().use { it.write(bytes) }
    return target.toString()
  }

  fun delete() {
    runCatching {
      SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it, mustExist = false) }
      SystemFileSystem.delete(directory, mustExist = false)
    }
  }

  /**
   * Where the output goes, or why it cannot go anywhere ffmpeg writes.
   */
  sealed interface SinkResult {
    class Ready(
      val path: String,
    ) : SinkResult

    class Refused(
      val error: FilmstripError,
    ) : SinkResult
  }

  companion object {
    /**
     * Makes a fresh directory under [root].
     *
     * @throws kotlinx.io.IOException When the directory cannot be made, which the caller reports as a failed export.
     */
    fun create(root: Path = SystemTemporaryDirectory): Scratch {
      val directory = Path(root, "filmstrip-${Random.nextToken()}")
      SystemFileSystem.createDirectories(directory)
      // On the JVM kotlinx-io returns quietly when the directory could not be made, so it is looked for afterwards.
      if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) {
        throw IOException("The scratch directory $directory could not be created.")
      }
      return Scratch(directory)
    }

    /**
     * Where the output goes.
     *
     * A temporary sink resolves outside the scratch directory, because it outlives the export. A
     * `file:` URI resolves to the path it names and a URI with no scheme is a path already. Any other
     * URI names nothing on this machine's filesystem and is refused.
     */
    fun resolveSink(sink: MediaSink): SinkResult =
      when (sink) {
        is MediaSink.Path -> {
          SinkResult.Ready(sink.path)
        }
        is MediaSink.Uri -> {
          val path = filePathOf(sink.uri) ?: sink.uri.takeIf { !it.contains("://") }
          if (path == null) {
            SinkResult.Refused(FilmstripError.SinkUnwritable(sink.uri, NOT_A_FILE_URL))
          } else {
            SinkResult.Ready(path)
          }
        }
        is MediaSink.Temporary -> {
          SinkResult.Ready(Path(SystemTemporaryDirectory, "filmstrip-export-${Random.nextToken()}.mp4").toString())
        }
      }
  }
}

/**
 * What this backend says about a sink URI that names no file on this machine.
 */
internal const val NOT_A_FILE_URL: String =
  "This backend writes to file URLs only. Copy the result out of a MediaSink.Temporary export to " +
    "reach anywhere else."

private fun Random.nextToken(): String = nextLong(0, Long.MAX_VALUE).toString(RADIX)

private const val RADIX = 36
