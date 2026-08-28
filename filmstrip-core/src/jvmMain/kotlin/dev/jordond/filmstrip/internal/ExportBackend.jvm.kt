package dev.jordond.filmstrip.internal

internal actual val EXPORT_BACKEND: ExportBackend =
  ExportBackend(artifact = FFMPEG_ARTIFACT, registration = "ffmpegBackend()")
