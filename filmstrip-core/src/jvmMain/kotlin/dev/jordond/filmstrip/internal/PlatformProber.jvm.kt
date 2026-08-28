package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult

// Android and Apple read metadata with an OS framework. Reading a container on the JVM means
// driving an external toolchain, which lives in the module that already spawns one, so core
// declines by name here and a registered prober answers ahead of it.
internal actual class PlatformProber actual constructor(
  @Suppress("unused") private val context: PlatformContext,
) {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    ProbeResult.Failure(
      ExportError.BackendMissing(
        artifact = FFMPEG_ARTIFACT,
        message =
          "`probe` has no toolchain-free implementation on the JVM. Add $FFMPEG_ARTIFACT and " +
            "register it with Filmstrip { ffmpegBackend() }, which reads metadata with ffprobe.",
      ),
    )
}
