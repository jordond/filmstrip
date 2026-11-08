package dev.jordond.filmstrip.ffmpeg.support

import java.io.File

/**
 * The host's ffmpeg, the one the build declared as this suite's input and passed in as `filmstrip.ffmpeg`.
 *
 * Every ffmpeg a test runs itself, to make a source or read a frame back, is this one. Throws when the property is
 * missing.
 */
internal val hostFfmpeg: String by lazy {
  checkNotNull(System.getProperty("filmstrip.ffmpeg")) {
    "filmstrip.ffmpeg is not set. Run these tests through Gradle, which finds ffmpeg from FILMSTRIP_FFMPEG or the PATH."
  }
}

/**
 * The host's ffprobe, the one the build declared as this suite's input and passed in as `filmstrip.ffprobe`.
 *
 * The build also hands it to the backend as `FILMSTRIP_FFPROBE`, so a test reads a written file with the same ffprobe
 * the backend probes with. Throws when the property is missing or names no file.
 */
internal val hostFfprobe: String by lazy {
  val ffprobe =
    checkNotNull(System.getProperty("filmstrip.ffprobe")) {
      "filmstrip.ffprobe is not set. Run these tests through Gradle, which finds ffprobe from FILMSTRIP_FFPROBE, " +
        "beside ffmpeg or on the PATH."
    }
  check(File(ffprobe).isFile) { "There is no ffprobe at $ffprobe, so the tests have nothing to read a file with." }
  ffprobe
}
