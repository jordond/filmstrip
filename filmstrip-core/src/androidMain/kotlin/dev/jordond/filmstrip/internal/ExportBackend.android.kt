package dev.jordond.filmstrip.internal

internal actual val EXPORT_BACKEND: ExportBackend =
  ExportBackend(artifact = MEDIA3_ARTIFACT, registration = "media3Backend()")
