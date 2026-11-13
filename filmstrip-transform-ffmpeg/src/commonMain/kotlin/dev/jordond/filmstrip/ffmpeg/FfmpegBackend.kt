package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.FilmstripRegistrar
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.OptionKey
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime

/**
 * Sets how the ffmpeg backend finds its binaries and what it passes them.
 *
 * The backend registers itself when `filmstrip-transform-ffmpeg` is a dependency and reads this config when the
 * instance first needs ffmpeg, so this call can go anywhere in the block. A later call replaces an earlier one.
 *
 * ```kotlin
 * val filmstrip = Filmstrip {
 *   ffmpeg { executablePath = "/opt/ffmpeg/bin/ffmpeg" }
 * }
 * ```
 *
 * @param block Sets the config's fields.
 * @return This builder.
 */
public fun FilmstripBuilder.ffmpeg(block: FfmpegConfig.Builder.() -> Unit): FilmstripBuilder =
  option(FFMPEG_CONFIG, FfmpegConfig.Builder().apply(block).build())

/**
 * Registers the ffmpeg backend, so `probe`, `plan`, `export` and `capabilities` work on the desktop.
 *
 * The backend registers itself when its artifact is a dependency, so a builder only calls this with discovery turned
 * off. It runs the config [ffmpeg] set, or the default one. A second call on the same builder does nothing.
 *
 * filmstrip ships no ffmpeg and links none of it. This drives an `ffmpeg` and an `ffprobe` that are
 * already on the machine, as separate programs over an argument list and a pipe. Nothing is
 * bundled, so nothing about the licence of the build a user installed reaches filmstrip's own
 * artifacts, and nothing about filmstrip's licence reaches theirs.
 *
 * Registration always succeeds, whether or not a binary is there. The four operations report
 * [FilmstripError.ToolchainMissing] when there is nothing usable to run, which is a value rather than
 * a failure to construct the graph.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.ffmpegBackend(): FilmstripBuilder = install(FfmpegRegistrar())

/**
 * Sets [config] the way [ffmpeg] does, then registers the ffmpeg backend.
 *
 * @param config How to find ffmpeg and what to pass it.
 * @return This builder.
 */
public fun FilmstripBuilder.ffmpegBackend(config: FfmpegConfig): FilmstripBuilder =
  option(FFMPEG_CONFIG, config).ffmpegBackend()

private val FFMPEG_CONFIG = OptionKey<FfmpegConfig>("ffmpeg")

internal class FfmpegRegistrar : FilmstripRegistrar {
  override val id: String = "filmstrip-transform-ffmpeg"

  override fun register(builder: FilmstripBuilder) {
    // One runtime behind both, so resolving the binaries, probing a clip and measuring the encoder ladder happen once
    // for every component. It is looked up on first use, after the block has set its config.
    val runtime = lazy { FfmpegRuntime.of(builder.optionOrNull(FFMPEG_CONFIG) ?: FfmpegConfig()) }
    builder
      .builtInEffects()
      .addExportEngineFactory(FfmpegEngineFactory(runtime))
      .addMediaProberFactory { FfmpegProber(runtime.value) }
      .addBackendInfo(BackendInfo(name = "ffmpeg", artifact = "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"))
  }
}

/**
 * Builds the engine every ffmpeg lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules.
 *
 * The runtime behind it is the one [config] already resolved through, so an engine built here and
 * a backend registered with the same config share one toolchain resolution, one probe cache and one
 * measurement of the encoder ladder. A caller lowering for a `Filmstrip` reads
 * [registeredFfmpegEngine] instead, so it runs the toolchain the host registered rather than one
 * built against a default it never asked for.
 *
 * @param components The components the owning `Filmstrip` was built with.
 * @param config How to find ffmpeg and what to pass it.
 * @return An engine that plans, resolves and exports on ffmpeg.
 */
@InternalFilmstripApi
public fun ffmpegExportEngine(
  components: EngineComponents,
  config: FfmpegConfig = FfmpegConfig(),
): FfmpegExportEngine = FfmpegExportEngine(components, FfmpegRuntime.of(config))

/**
 * The engine the [ffmpegBackend] registered in this registry builds, or null when none was registered.
 *
 * The engine runs the toolchain the registration's config names, so a preview lowering through it resolves the same
 * binaries an export of the same `Filmstrip` does. The latest registration wins.
 *
 * @return The registered engine, or null when this registry has no ffmpeg backend.
 */
@InternalFilmstripApi
public fun FilmstripComponents.registeredFfmpegEngine(): FfmpegExportEngine? =
  exportEngineFactories.firstNotNullOfOrNull { (it as? FfmpegEngineFactory)?.create(this) }

/**
 * Builds engines over one registration's runtime, which is what [registeredFfmpegEngine] finds it by.
 */
private class FfmpegEngineFactory(
  private val runtime: Lazy<FfmpegRuntime>,
) : ExportEngineFactory {
  override fun create(components: EngineComponents): FfmpegExportEngine = FfmpegExportEngine(components, runtime.value)
}
