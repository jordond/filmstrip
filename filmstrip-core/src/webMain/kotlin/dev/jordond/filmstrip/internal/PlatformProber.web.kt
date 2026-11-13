package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult

// Android and Apple probe with a read-only OS API. Reading a container in a browser means demuxing
// it, and the demuxer is an npm dependency of the webcodecs module that core's layering row rules
// out here, so core declines a container by naming that module and its registered prober answers
// ahead of this one.
//
// A still is the one source core answers itself, because it carries no container to demux: the
// browser reports the bounds and the source declares the duration.
internal actual class PlatformProber actual constructor() {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    when (source) {
      is MediaSource.Image -> {
        probeImage(source)
      }
      is MediaSource.Path, is MediaSource.Uri, is MediaSource.Bytes -> {
        ProbeResult.Failure(
          FilmstripError.BackendMissing(
            artifact = EXPORT_BACKEND.artifact,
            message =
              "`probe` needs a demuxer in a browser, and filmstrip-core carries none. Add " +
                "${EXPORT_BACKEND.artifact}, which registers itself and reads metadata with mediabunny, or call " +
                "${EXPORT_BACKEND.registration} on a builder with autoRegister(false).",
          ),
        )
      }
    }
}
