package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.random.Random

/**
 * One directory per export, for the things ffmpeg can only read from a file.
 *
 * Deleted on completion, on failure and on cancellation. The output is deliberately not in here:
 * `MediaSink.Temporary` belongs to the caller once the export finishes, and filmstrip never deletes
 * it.
 */
internal class Scratch private constructor(
  private val directory: Path,
) {
  private var counter = 0

  fun materialise(image: ImageSource): String =
    when (image) {
      is ImageSource.Path -> image.path
      is ImageSource.Uri -> image.uri.removePrefix("file://")
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

  companion object {
    fun create(): Scratch {
      val directory = Path(SystemTemporaryDirectory, "filmstrip-${Random.nextULong()}")
      SystemFileSystem.createDirectories(directory)
      return Scratch(directory)
    }

    /**
     * Where the output goes.
     *
     * A temporary sink resolves outside the scratch directory, because it outlives the export.
     */
    fun resolveSink(sink: MediaSink): String =
      when (sink) {
        is MediaSink.Path -> {
          sink.path
        }
        is MediaSink.Uri -> {
          sink.uri.removePrefix("file://")
        }
        is MediaSink.Temporary -> {
          Path(SystemTemporaryDirectory, "filmstrip-export-${Random.nextULong()}.mp4").toString()
        }
      }
  }
}

private fun Random.nextULong(): String = nextLong(0, Long.MAX_VALUE).toString(RADIX)

private const val RADIX = 36
