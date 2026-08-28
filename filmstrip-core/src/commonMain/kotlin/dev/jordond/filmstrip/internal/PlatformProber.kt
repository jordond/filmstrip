package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult

// Reads a source's metadata without decoding it, using read-only platform APIs only.
internal expect class PlatformProber() {
  /**
   * Reads [source], or returns why it could not be read.
   */
  suspend fun probe(source: MediaSource): ProbeResult
}
