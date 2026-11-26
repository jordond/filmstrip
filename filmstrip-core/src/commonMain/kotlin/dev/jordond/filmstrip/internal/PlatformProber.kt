package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult

// Reads a source's metadata without decoding it, using read-only platform APIs only.
internal expect class PlatformProber() {
  /**
   * Reads [source], or returns why it could not be read.
   */
  suspend fun read(source: MediaSource): SourceRead
}

/**
 * Reads [source] and gives the answer [dev.jordond.filmstrip.Filmstrip.probe] reports for it.
 */
internal suspend fun PlatformProber.probe(source: MediaSource): ProbeResult = read(source).toProbeResult()
