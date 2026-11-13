package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.OptionKey

/**
 * A place the ffmpeg backend can get its binaries from, other than the machine's own install.
 *
 * The backend asks it when neither [FfmpegConfig.executablePath] nor `FILMSTRIP_FFMPEG` names an ffmpeg, and before it
 * searches `PATH`. What it hands back is checked like any other ffmpeg, for its version and for what the build
 * carries. A runtime is shared per instance of this, so an artifact supplies one instance for the whole process.
 */
@InternalFilmstripApi
public fun interface FfmpegBinaries {
  /**
   * Finds the binaries, doing whatever work makes them runnable first.
   *
   * @return Where they are, or why there are none for this machine.
   */
  public suspend fun locate(): FfmpegBinariesResult
}

/**
 * What an [FfmpegBinaries] found.
 */
@InternalFilmstripApi
public sealed interface FfmpegBinariesResult {
  /**
   * A pair of binaries that belong together.
   *
   * @property ffmpeg The absolute path to `ffmpeg`.
   * @property ffprobe The absolute path to the `ffprobe` of the same build.
   */
  @InternalFilmstripApi
  public class Found(
    public val ffmpeg: String,
    public val ffprobe: String,
  ) : FfmpegBinariesResult

  /**
   * Nothing to run on this machine. The backend reports [message] and does not search `PATH`.
   *
   * @property message Why there is no binary, and what the caller can do about it.
   */
  @InternalFilmstripApi
  public class Unavailable(
    public val message: String,
  ) : FfmpegBinariesResult
}

/**
 * Sets where the ffmpeg backend of this builder gets its binaries, replacing any earlier source.
 *
 * @param binaries The source to ask.
 * @return This builder.
 */
@InternalFilmstripApi
public fun FilmstripBuilder.ffmpegBinaries(binaries: FfmpegBinaries): FilmstripBuilder =
  option(FFMPEG_BINARIES, binaries)

internal val FFMPEG_BINARIES = OptionKey<FfmpegBinaries>("ffmpeg-binaries")
