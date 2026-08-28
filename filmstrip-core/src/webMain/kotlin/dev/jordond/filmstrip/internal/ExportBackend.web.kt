package dev.jordond.filmstrip.internal

internal actual val EXPORT_BACKEND: ExportBackend =
  ExportBackend(artifact = WEB_CODECS_ARTIFACT, registration = "webCodecsBackend()")
