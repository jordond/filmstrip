package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.probeImage

// Android and Apple read metadata with an OS framework. Reading a container on the JVM means
// driving an external toolchain, which lives in the module that already spawns one, so core
// declines a container by name here and a registered prober answers ahead of it.
//
// A still is the one source core answers itself, because it carries no container to read: the JDK's
// own ImageIO reports the bounds and the source declares the duration.
internal actual class PlatformProber actual constructor() {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    when (source) {
      is MediaSource.Image -> {
        probeImage(source)
      }
      is MediaSource.Path, is MediaSource.Uri, is MediaSource.Bytes -> {
        ProbeResult.Failure(
          ExportError.BackendMissing(
            artifact = FFMPEG_ARTIFACT,
            message =
              "`probe` has no toolchain-free implementation on the JVM. Add $FFMPEG_ARTIFACT and " +
                "register it with Filmstrip { ffmpegBackend() }, which reads metadata with ffprobe.",
          ),
        )
      }
    }
}
