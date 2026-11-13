package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.FilmstripRegistrar
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.ffmpeg.ffmpegBinaries

/**
 * Registers the ffmpeg backend over the ffmpeg and ffprobe this artifact carries.
 *
 * The artifact registers itself when it is a dependency, so a builder only calls this with discovery turned off. The
 * binaries are unpacked into javacpp's cache the first time the instance needs ffmpeg and run from there. A path set
 * through `ffmpeg { executablePath = ... }` or `FILMSTRIP_FFMPEG` is still used ahead of them, and the machine's
 * `PATH` is never searched.
 *
 * On a platform this artifact has no binary for, every operation reports `FilmstripError.ToolchainMissing` naming the
 * platform.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.ffmpegBundled(): FilmstripBuilder = install(BundledFfmpegRegistrar())

internal class BundledFfmpegRegistrar : FilmstripRegistrar {
  override val id: String = "filmstrip-transform-ffmpeg-bundled"

  override fun register(builder: FilmstripBuilder) {
    builder.ffmpegBinaries(BundledBinaries.Default).ffmpegBackend()
  }
}
