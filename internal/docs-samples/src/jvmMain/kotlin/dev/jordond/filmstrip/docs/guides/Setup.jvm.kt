package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.player.playerBackend

internal fun setupFfmpeg(): Filmstrip {
  // region setup-ffmpeg
  val filmstrip =
    Filmstrip {
      ffmpegBackend(
        FfmpegConfig(
          executablePath = "/opt/homebrew/bin/ffmpeg",
          probePath = "/opt/homebrew/bin/ffprobe",
        ),
      )
      playerBackend()
    }
  // endregion
  return filmstrip
}
