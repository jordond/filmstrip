package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe

/**
 * Reads metadata with ffprobe.
 *
 * The same binary, the same cache and the same parser the planner already uses, so probing a clip
 * and then exporting it spawns one ffprobe rather than two. Two probers over one runtime are equal, so a chain that
 * holds both asks ffprobe once.
 */
internal data class FfmpegProber(
  private val runtime: FfmpegRuntime,
) : MediaProber {
  override suspend fun probe(source: MediaSource): ProbeResult {
    val path =
      readablePath(source)
        ?: return ProbeResult.Failure(FilmstripError.SourceUnreadable(source.describe(), READS_FILES))

    return when (val outcome = runtime.probeSource(path)) {
      is ProbeOutcome.Read -> ProbeResult.Success(outcome.info)
      is ProbeOutcome.NoToolchain -> ProbeResult.Failure(outcome.error)
      ProbeOutcome.Unreadable -> ProbeResult.Failure(FilmstripError.SourceUnreadable(source.describe(), UNREADABLE))
    }
  }

  private companion object {
    const val UNREADABLE = "ffprobe found no readable track in the source."
  }
}
