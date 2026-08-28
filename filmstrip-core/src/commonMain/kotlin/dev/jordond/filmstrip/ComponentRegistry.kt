package dev.jordond.filmstrip

import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.player.PlayerEngineFactory
import dev.jordond.filmstrip.thumbnail.ThumbnailSourceFactory

/**
 * Every pluggable piece a [Filmstrip] instance was built with.
 *
 * Components are always registered explicitly, through [Builder]. Later registrations win: each
 * `add` inserts at the front, so registering your own after a built-in overrides it.
 *
 * @property effectResolvers Resolvers that lower an effect spec to something an engine can run.
 * @property playerEngineFactories Factories that build a preview player.
 * @property thumbnailSourceFactories Factories that build a thumbnail source.
 * @property exportEngineFactories Factories that build an export engine.
 * @property mediaProberFactories Factories that build a media prober. Core's own platform prober
 *   is consulted after every one of these, so an empty list still probes on a target that can.
 */
public class ComponentRegistry internal constructor(
  public val effectResolvers: List<EffectResolver>,
  public val playerEngineFactories: List<PlayerEngineFactory>,
  public val thumbnailSourceFactories: List<ThumbnailSourceFactory>,
  @property:InternalFilmstripApi public val exportEngineFactories: List<ExportEngineFactory>,
  @property:InternalFilmstripApi public val mediaProberFactories: List<MediaProberFactory>,
) {
  /**
   * Starts a builder from what is already registered here.
   *
   * @return A builder carrying every component in this registry, in the same order.
   */
  public fun newBuilder(): Builder = Builder(this)

  /**
   * Accumulates components, front-first so later registrations take precedence.
   */
  public class Builder {
    private val effectResolvers = mutableListOf<EffectResolver>()
    private val playerEngineFactories = mutableListOf<PlayerEngineFactory>()
    private val thumbnailSourceFactories = mutableListOf<ThumbnailSourceFactory>()
    private val exportEngineFactories = mutableListOf<ExportEngineFactory>()
    private val mediaProberFactories = mutableListOf<MediaProberFactory>()

    /**
     * An empty builder.
     */
    public constructor()

    /**
     * A builder carrying everything in [registry].
     */
    public constructor(registry: ComponentRegistry) {
      effectResolvers += registry.effectResolvers
      playerEngineFactories += registry.playerEngineFactories
      thumbnailSourceFactories += registry.thumbnailSourceFactories
      exportEngineFactories += registry.exportEngineFactories
      mediaProberFactories += registry.mediaProberFactories
    }

    /**
     * Registers an effect resolver, ahead of everything already registered.
     */
    public fun add(resolver: EffectResolver): Builder = apply { effectResolvers.add(0, resolver) }

    /**
     * Registers a player engine factory, ahead of everything already registered.
     */
    public fun add(factory: PlayerEngineFactory): Builder = apply { playerEngineFactories.add(0, factory) }

    /**
     * Registers a thumbnail source factory, ahead of everything already registered.
     */
    public fun add(factory: ThumbnailSourceFactory): Builder = apply { thumbnailSourceFactories.add(0, factory) }

    /**
     * Registers an export engine factory, ahead of everything already registered.
     */
    @InternalFilmstripApi
    public fun add(factory: ExportEngineFactory): Builder = apply { exportEngineFactories.add(0, factory) }

    /**
     * Registers a media prober factory, ahead of everything already registered.
     */
    @InternalFilmstripApi
    public fun add(factory: MediaProberFactory): Builder = apply { mediaProberFactories.add(0, factory) }

    /**
     * Freezes what has been registered.
     */
    public fun build(): ComponentRegistry =
      ComponentRegistry(
        effectResolvers = effectResolvers.toList(),
        playerEngineFactories = playerEngineFactories.toList(),
        thumbnailSourceFactories = thumbnailSourceFactories.toList(),
        exportEngineFactories = exportEngineFactories.toList(),
        mediaProberFactories = mediaProberFactories.toList(),
      )
  }
}
