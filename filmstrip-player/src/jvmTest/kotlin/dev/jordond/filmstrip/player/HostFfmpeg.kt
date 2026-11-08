package dev.jordond.filmstrip.player

/**
 * The host's ffmpeg, the one the build declared as this suite's input and passed in as `filmstrip.ffmpeg`.
 *
 * Every ffmpeg a test runs itself, to make a source or read a written file back, is this one. Throws when the
 * property is missing.
 */
internal val hostFfmpeg: String by lazy {
  checkNotNull(System.getProperty("filmstrip.ffmpeg")) {
    "filmstrip.ffmpeg is not set. Run these tests through Gradle, which finds ffmpeg from FILMSTRIP_FFMPEG or the PATH."
  }
}
