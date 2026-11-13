package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import java.io.File

// Writes a stand-in binary that prints [bitstreamFilters] when asked for them and [banner] for anything else.
internal fun toolchainScript(
  directory: File,
  name: String,
  banner: String,
  bitstreamFilters: String = "",
): File =
  File(directory, name).apply {
    writeText(
      "#!/bin/sh\ncase \"$*\" in *-bsfs*) printf '%s\\n' '$bitstreamFilters' ;; " +
        "*) printf '%s\\n' '$banner' ;; esac\n",
    )
    setExecutable(true)
  }

// Writes a stand-in ffmpeg and ffprobe at [version] into a new directory under [root] called [name].
internal fun toolchainStub(
  root: File,
  name: String,
  version: String,
): File =
  File(root, name).apply {
    mkdirs()
    val banner = "ffmpeg version $version Copyright (c) 2000-2025 the FFmpeg developers"
    toolchainScript(this, "ffmpeg", banner, "Bitstream filters:\n$PACKET_DROP_FILTER")
    toolchainScript(this, "ffprobe", "ffprobe version $version")
  }
