package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.ffmpeg.bundled.ffmpegBundled
import dev.jordond.filmstrip.ffmpeg.ffmpeg

internal fun setupFfmpeg(): Filmstrip {
  // region setup-ffmpeg
  val filmstrip =
    Filmstrip {
      ffmpeg {
        executablePath = "/opt/homebrew/bin/ffmpeg"
        probePath = "/opt/homebrew/bin/ffprobe"
      }
    }
  // endregion
  return filmstrip
}

internal fun setupFfmpegBundled(): Filmstrip {
  // region setup-ffmpeg-bundled
  val filmstrip =
    Filmstrip {
      autoRegister(false)
      ffmpegBundled()
    }
  // endregion
  return filmstrip
}
