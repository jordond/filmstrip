package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe

// Android and Apple probe with a read-only OS API. Reading a container's tracks in a browser means
// demuxing it, and a demuxer is an npm dependency that core's layering row rules out, so core
// declines by name here and a registered prober answers ahead of it.
internal actual class PlatformProber actual constructor() {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    ProbeResult.Failure(
      ExportError.SourceUnreadable(source = source.describe(), message = NO_DEMUXER),
    )

  private companion object {
    const val NO_DEMUXER =
      "Probing in a browser needs a demuxer, and filmstrip-core carries none. Nothing registers " +
        "one on this target yet."
  }
}
