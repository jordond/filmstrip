package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaInfo
import kotlin.time.Duration

/**
 * What [FfmpegRuntime.probe] found, separating "there is nothing to run" from "it ran and could not
 * read the file", which are different failures with different fixes.
 */
internal sealed interface ProbeOutcome {
  class Read(
    val info: MediaInfo,
  ) : ProbeOutcome

  class NoToolchain(
    val error: FilmstripError.ToolchainMissing,
  ) : ProbeOutcome

  data object Unreadable : ProbeOutcome

  data object Undecodable : ProbeOutcome

  class Expired(
    val operation: String,
    val deadline: Duration,
    val reason: String,
  ) : ProbeOutcome
}
