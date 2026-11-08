package dev.jordond.filmstrip.effects.filters

/**
 * The host's ffmpeg, the one the build declared as this suite's input and passed in as `filmstrip.ffmpeg`.
 *
 * Throws when the property is missing, since every test reading it runs a filter graph through that ffmpeg.
 */
internal val hostFfmpeg: String by lazy {
  checkNotNull(System.getProperty("filmstrip.ffmpeg")) {
    "filmstrip.ffmpeg is not set. Run these tests through Gradle, which finds ffmpeg from FILMSTRIP_FFMPEG or the PATH."
  }
}
