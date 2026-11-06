package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource

internal fun sources(bytes: ByteArray): List<MediaSource> {
  // region sources
  val fromPath = MediaSource.of("/path/to/clip.mp4")
  val fromUri = MediaSource.ofUri("content://media/external/video/media/42")
  val fromBytes = MediaSource.ofBytes(bytes, hint = FormatHint.Mp4)
  // endregion
  return listOf(fromPath, fromUri, fromBytes)
}

internal fun sinks(): List<MediaSink> {
  // region sinks
  val toPath = MediaSink.of("/path/to/out.mp4")
  val toUri = MediaSink.ofUri("content://media/external/video/media/43")
  val toTemporary = MediaSink.temporary()
  // endregion
  return listOf(toPath, toUri, toTemporary)
}

internal fun temporaryOutput(status: ExportStatus) {
  // region temporary-output
  if (status is ExportStatus.Success) {
    val written = status.output as MediaSink.Path
    println("Exported to ${written.path}")
  }
  // endregion
}
