package dev.jordond.filmstrip.ffmpeg

import dev.drewhamilton.poko.Poko

/**
 * How the ffmpeg backend finds its binaries and what it passes them.
 *
 * @property executablePath An absolute path to `ffmpeg`, or null to read `FILMSTRIP_FFMPEG` from
 *   the environment and then search `PATH`.
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
)
