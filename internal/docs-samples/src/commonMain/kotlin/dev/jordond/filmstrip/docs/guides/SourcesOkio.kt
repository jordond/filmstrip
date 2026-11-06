package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.okio.toMediaSink
import dev.jordond.filmstrip.okio.toMediaSource
import okio.Path.Companion.toPath

internal fun okioSources(): Pair<MediaSource, MediaSink> {
  // region okio
  val source = "/path/to/clip.mp4".toPath().toMediaSource()
  val sink = "/path/to/out.mp4".toPath().toMediaSink()
  // endregion
  return source to sink
}
