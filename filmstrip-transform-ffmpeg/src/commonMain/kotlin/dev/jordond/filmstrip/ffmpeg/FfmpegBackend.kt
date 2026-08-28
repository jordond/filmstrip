package dev.jordond.filmstrip.ffmpeg

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime

/**
 * Registers the ffmpeg backend, so `probe`, `plan`, `export` and `capabilities` work on the desktop.
 *
 * filmstrip ships no ffmpeg and links none of it. This drives an `ffmpeg` and an `ffprobe` that are
 * already on the machine, as separate programs over an argument list and a pipe. Nothing is
 * bundled, so nothing about the licence of the build a user installed reaches filmstrip's own
 * artifacts, and nothing about filmstrip's licence reaches theirs.
 *
 * Registration always succeeds, whether or not a binary is there. The four operations report
 * [ExportError.ToolchainMissing] when there is nothing usable to run, which is a value rather than
 * a failure to construct the graph.
 *
 * Register it after another engine backend on a platform that has both and this one wins, because
 * each registration goes in at the front.
 *
 * @param config How to find ffmpeg and what to pass it.
 * @return This builder.
 */
@OptIn(InternalFilmstripApi::class)
public fun FilmstripBuilder.ffmpegBackend(config: FfmpegConfig = FfmpegConfig()): FilmstripBuilder {
  // One runtime behind both, so resolving the binaries and probing a clip happen once rather than
  // once per component.
  val runtime = FfmpegRuntime(config)
  return builtInEffects()
    .addExportEngineFactory { _, components -> FfmpegExportEngine(components, runtime) }
    .addMediaProberFactory { FfmpegProber(runtime) }
    .addBackendInfo(BackendInfo(name = "ffmpeg", artifact = "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
}

/**
 * How the ffmpeg backend finds its binaries and what it passes them.
 *
 * A class rather than a parameter list so a field can be added without breaking a caller.
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
