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
 * Creates a [Filmstrip] from every filmstrip artifact on the classpath, plus whatever [block] registers.
 *
 * Each backend, the player and the filters register themselves when their artifact is a dependency, so
 * `Filmstrip()` with `dev.jordond.filmstrip:filmstrip` can probe, plan, export and preview. What [block] registers
 * goes ahead of what was discovered. Call [FilmstripBuilder.autoRegister] with false to register only what [block]
 * asks for.
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
  private val installed = mutableSetOf<RegistrarId>()
  private var options = mutableMapOf<OptionKey<*>, Any>()
  private var autoRegister = true

  /**
   * Sets whether [build] adds the artifacts it discovers on the classpath. On by default.
   *
   * Turned off, the instance has only what this builder registered, which is how a test builds one with a backend
   * missing.
   *
   * @param enabled Whether to add discovered artifacts.
   * @return This builder.
   */
  public fun autoRegister(enabled: Boolean): FilmstripBuilder = apply { autoRegister = enabled }

  /**
   * Runs [registrar] unless one with the same id already ran on this builder.
   *
   * @param registrar The artifact's registrar.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun install(registrar: FilmstripRegistrar): FilmstripBuilder =
    apply { if (installed.add(registrar.id)) registrar.register(this) }

  /**
   * Sets the value a registrar reads for [key], replacing any earlier one.
   *
   * Every registrar of one instance reads the same options, discovered or installed by the block, so a registrar
   * that reads lazily sees a value set after it ran.
   *
   * @param key The option.
   * @param value Its value.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun <T : Any> option(
    key: OptionKey<T>,
    value: T,
  ): FilmstripBuilder = apply { options[key] = value }

  /**
   * The value set for [key], or null when nothing set one.
   *
   * @param key The option.
   * @return Its value.
   */
  @InternalFilmstripApi
  public fun <T : Any> optionOrNull(key: OptionKey<T>): T? {
    @Suppress("UNCHECKED_CAST")
    return options[key] as T?
  }

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
   *
   * Unless [autoRegister] turned it off, every discovered artifact this builder did not install is added behind what
   * it registered.
   */
  public fun build(): Filmstrip = DefaultFilmstrip(components())

  internal fun components(): ComponentRegistry {
    val own = registry.build()
    if (!autoRegister) return own
    val discovered = FilmstripRegistrars.discovered().filterNot { it.id in installed }
    if (discovered.isEmpty()) return own
    // A second builder sharing this one's options and starting from its installed ids, so registrars on either side
    // read what the other set and a dependency the block installed is not added twice.
    val seed = FilmstripBuilder()
    seed.installed += installed
    seed.options = options
    discovered.forEach { seed.install(it) }
    return own.layeredOver(seed.registry.build())
  }
}
