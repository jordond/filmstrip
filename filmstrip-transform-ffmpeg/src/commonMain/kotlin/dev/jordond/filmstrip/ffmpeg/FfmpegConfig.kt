package dev.jordond.filmstrip.ffmpeg

import dev.drewhamilton.poko.Poko

/**
 * How the ffmpeg backend finds its binaries and what it passes them.
 *
 * @property executablePath An absolute path to `ffmpeg`, or null to read `FILMSTRIP_FFMPEG` from the environment,
 *   then take the one `filmstrip-transform-ffmpeg-bundled` carries when it is a dependency, then search `PATH`.
 * @property probePath An absolute path to `ffprobe`, or null to resolve it the same way from
 *   `FILMSTRIP_FFPROBE`, then `PATH`, then beside whichever `ffmpeg` was found.
 * @property extraArgs Arguments appended to every encode, immediately before the output file.
 *   An escape hatch, and the thing that makes an edit non-portable: nothing here is understood by
 *   any other backend, so an edit that needs it renders differently everywhere else.
 * @property threads How many threads to allow, or null to let ffmpeg decide.
 */
@Poko
public class FfmpegConfig(
  public val executablePath: String? = null,
  public val probePath: String? = null,
  public val extraArgs: List<String> = emptyList(),
  public val threads: Int? = null,
) {
  /**
   * Collects an [FfmpegConfig] field by field, for `ffmpeg { }` on a `FilmstripBuilder`.
   *
   * Every field starts at the [FfmpegConfig] default.
   */
  public class Builder {
    /**
     * An absolute path to `ffmpeg`, or null to read `FILMSTRIP_FFMPEG`, then take the one
     * `filmstrip-transform-ffmpeg-bundled` carries when it is a dependency, then search `PATH`.
     */
    public var executablePath: String? = null

    /**
     * An absolute path to `ffprobe`, or null to resolve it the way [FfmpegConfig.probePath] does.
     */
    public var probePath: String? = null

    /**
     * Arguments appended to every encode, immediately before the output file.
     */
    public var extraArgs: List<String> = emptyList()

    /**
     * How many threads to allow, or null to let ffmpeg decide.
     */
    public var threads: Int? = null

    /**
     * Builds the config these fields describe.
     *
     * @return The config.
     */
    public fun build(): FfmpegConfig = FfmpegConfig(executablePath, probePath, extraArgs, threads)
  }
}
