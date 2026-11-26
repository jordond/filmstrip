package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.ffmpeg.internal.process.sampleTableOverrunAt
import dev.jordond.filmstrip.internal.SourceProber
import dev.jordond.filmstrip.internal.SourceRead
import dev.jordond.filmstrip.internal.noVideoDecoder
import dev.jordond.filmstrip.internal.truncatedSource
import dev.jordond.filmstrip.internal.undecodableSource
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads metadata with ffprobe, after refusing an MP4 or QuickTime file cut short of its last sample.
 *
 * The same binary, the same cache and the same parser the planner already uses, so probing a clip
 * and then exporting it spawns one ffprobe rather than two. Two probers over one runtime are equal, so a chain that
 * holds both asks ffprobe once.
 */
internal data class FfmpegProber(
  private val runtime: FfmpegRuntime,
) : SourceProber {
  override suspend fun read(source: MediaSource): SourceRead {
    val path =
      try {
        withContext(Dispatchers.IO) { readablePath(source, runtime.bytesRoot) }
          ?: return SourceRead.Failed(FilmstripError.SourceUnreadable(source.describe(), READS_FILES))
      } catch (failure: MediaBytesFileFailure) {
        return SourceRead.Failed(FilmstripError.SourceUnreadable(failure.source, failure.message))
      }

    sampleTableOverrunAt(path)?.let { missing ->
      return SourceRead.Failed(truncatedSource(source.describe(), missing))
    }
    return when (val outcome = runtime.probeSource(path)) {
      is ProbeOutcome.Read -> {
        SourceRead.Read(outcome.info)
      }
      is ProbeOutcome.NoToolchain -> {
        SourceRead.Failed(outcome.error)
      }
      is ProbeOutcome.NoDecoder -> {
        noVideoDecoder(outcome.info, outcome.codec)
      }
      ProbeOutcome.Undecodable -> {
        SourceRead.Failed(undecodableSource(source.describe()))
      }
      ProbeOutcome.Unreadable -> {
        SourceRead.Failed(FilmstripError.SourceUnreadable(source.describe(), UNREADABLE))
      }
      is ProbeOutcome.Expired -> {
        SourceRead.Failed(FilmstripError.TimedOut(outcome.operation, outcome.deadline, outcome.reason))
      }
    }
  }

  private companion object {
    const val UNREADABLE = "ffprobe found no readable track in the source."
  }
}
