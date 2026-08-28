package dev.jordond.filmstrip.internal

/**
 * Which artifact owns encoding on this target, and the call that registers it.
 *
 * @property artifact The Maven coordinate to add.
 * @property registration The builder call that registers it, without a receiver.
 */
internal class ExportBackend(
  val artifact: String,
  val registration: String,
)

// Per-target because each target names its own engine artifact.
internal expect val EXPORT_BACKEND: ExportBackend

internal const val MEDIA3_ARTIFACT: String = "dev.jordond.filmstrip:filmstrip-transform-media3"

internal const val AV_FOUNDATION_ARTIFACT: String = "dev.jordond.filmstrip:filmstrip-transform-avfoundation"

internal const val WEB_CODECS_ARTIFACT: String = "dev.jordond.filmstrip:filmstrip-transform-webcodecs"

internal const val FFMPEG_ARTIFACT: String = "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"
