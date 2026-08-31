package dev.jordond.filmstrip.ffmpeg

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ComponentRegistry
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
  // One runtime behind both, so resolving the binaries, probing a clip and measuring the encoder
  // ladder happen once rather than once per component. The factory runs per caller, so the engine
  // it hands back holds nothing worth keeping that the runtime does not already hold.
  val runtime = FfmpegRuntime.of(config)
  return builtInEffects()
    .addExportEngineFactory { components -> FfmpegExportEngine(components, runtime) }
    .addMediaProberFactory { FfmpegProber(runtime) }
    .addBackendInfo(BackendInfo(name = "ffmpeg", artifact = "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
}

/**
 * Builds the engine every ffmpeg lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules rather than against two sets that
 * have to be kept in step.
 *
 * The runtime behind it is the one [config] already resolved through, so an engine built here and
 * a backend registered with the same config share one toolchain resolution, one probe cache and one
 * measurement of the encoder ladder. A caller that registered [ffmpegBackend] with a config of its
 * own should reach for the registered engine rather than building one against a default it never
 * asked for.
 *
 * @param components The components the owning `Filmstrip` was built with.
 * @param config How to find ffmpeg and what to pass it.
 * @return An engine that plans, resolves and exports on ffmpeg.
 */
@InternalFilmstripApi
public fun ffmpegExportEngine(
  components: ComponentRegistry,
  config: FfmpegConfig = FfmpegConfig(),
): FfmpegExportEngine = FfmpegExportEngine(components, FfmpegRuntime.of(config))

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
