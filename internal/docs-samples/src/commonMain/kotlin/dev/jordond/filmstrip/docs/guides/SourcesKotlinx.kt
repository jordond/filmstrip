package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.kotlinxio.toMediaSink
import dev.jordond.filmstrip.kotlinxio.toMediaSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.io.files.Path

internal fun kotlinxSources(): Pair<MediaSource, MediaSink> {
  // region kotlinx-io
  val source = Path("/path/to/clip.mp4").toMediaSource()
  val sink = Path("/path/to/out.mp4").toMediaSink()
  // endregion
  return source to sink
}
