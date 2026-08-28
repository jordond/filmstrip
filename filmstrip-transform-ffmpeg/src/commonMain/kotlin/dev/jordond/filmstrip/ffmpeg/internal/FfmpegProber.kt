package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe

/**
 * Reads metadata with ffprobe.
 *
 * The same binary, the same cache and the same parser the planner already uses, so probing a clip
 * and then exporting it spawns one ffprobe rather than two.
 */
@OptIn(InternalFilmstripApi::class)
internal class FfmpegProber(
  private val runtime: FfmpegRuntime,
) : MediaProber {
  override suspend fun probe(source: MediaSource): ProbeResult {
    val path =
      readablePath(source)
        ?: return ProbeResult.Failure(ExportError.SourceUnreadable(source.describe(), READS_FILES))

    return when (val outcome = runtime.probeSource(path)) {
      is ProbeOutcome.Read -> ProbeResult.Success(outcome.info)
      is ProbeOutcome.NoToolchain -> ProbeResult.Failure(outcome.error)
      ProbeOutcome.Unreadable -> ProbeResult.Failure(ExportError.SourceUnreadable(source.describe(), UNREADABLE))
    }
  }

  private companion object {
    const val UNREADABLE = "ffprobe found no readable track in the source."
  }
}
