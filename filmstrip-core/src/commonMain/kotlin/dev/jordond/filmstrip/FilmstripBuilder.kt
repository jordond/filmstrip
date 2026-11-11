package dev.jordond.filmstrip

import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.internal.DefaultFilmstrip
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.player.PlayerEngineFactory
import dev.jordond.filmstrip.thumbnail.ThumbnailSourceFactory

/**
 * Creates a [Filmstrip], registering whatever [block] asks for and nothing else.
 *
 * An instance built with an empty block can only [Filmstrip.probe], and only on Android and Apple.
 * Depend on `dev.jordond.filmstrip:filmstrip` and call `allBackends()` in [block] to register every
 * backend it bundles.
 *
 * On Android the application context comes from App Startup. Where App Startup is disabled or does
 * not run, such as a bare JVM test, install one with the Android-only `Filmstrip(context)` overload.
 *
 * @param block Registers components on the builder.
 * @return The configured instance.
 */
public fun Filmstrip(block: FilmstripBuilder.() -> Unit = {}): Filmstrip = FilmstripBuilder().apply(block).build()

/**
 * Assembles a [Filmstrip].
 *
 * Also the Swift entry point: `FilmstripBuilder()`, then the explicit `add` calls, then [build].
 */
@FilmstripDsl
public class FilmstripBuilder {
  private var registry: ComponentRegistry.Builder = ComponentRegistry.Builder()

  /**
   * Registers components through a receiver lambda.
   *
   * @param block Registers components on the registry builder.
   * @return This builder.
   */
  public fun components(block: ComponentRegistry.Builder.() -> Unit): FilmstripBuilder = apply { registry.block() }

  /**
   * Registers one effect resolver.
   *
   * @param resolver The resolver to register.
   * @return This builder.
   */
  public fun addEffectResolver(resolver: EffectResolver): FilmstripBuilder =
    apply { registry.addEffectResolver(resolver) }

  /**
   * Registers one audio effect resolver.
   *
   * @param resolver The resolver to register.
   * @return This builder.
   */
  public fun addAudioEffectResolver(resolver: AudioEffectResolver): FilmstripBuilder =
    apply { registry.addAudioEffectResolver(resolver) }

  /**
   * Registers one player engine factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  public fun addPlayerEngineFactory(factory: PlayerEngineFactory): FilmstripBuilder =
    apply { registry.addPlayerEngineFactory(factory) }

  /**
   * Registers one thumbnail source factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  public fun addThumbnailSourceFactory(factory: ThumbnailSourceFactory): FilmstripBuilder =
    apply { registry.addThumbnailSourceFactory(factory) }

  /**
   * Registers one export engine factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addExportEngineFactory(factory: ExportEngineFactory): FilmstripBuilder =
    apply { registry.addExportEngineFactory(factory) }

  /**
   * Registers one media prober factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addMediaProberFactory(factory: MediaProberFactory): FilmstripBuilder =
    apply { registry.addMediaProberFactory(factory) }

  /**
   * Records what a backend calls itself, so a diagnostic report can name it.
   *
   * @param info The backend's identity.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addBackendInfo(info: BackendInfo): FilmstripBuilder = apply { registry.addBackendInfo(info) }

  /**
   * Registers a listener for what the components learn while they run.
   *
   * @param listener The listener to register.
   * @return This builder.
   */
  public fun addDiagnosticListener(listener: DiagnosticListener): FilmstripBuilder =
    apply { registry.addDiagnosticListener(listener) }

  /**
   * Builds the configured [Filmstrip].
   */
  public fun build(): Filmstrip = DefaultFilmstrip(registry.build())
}
