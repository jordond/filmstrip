package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.FilmstripRegistrar
import dev.jordond.filmstrip.effects.builtInEffects

/**
 * Registers the preview backend, so `preview` and effect-applied frames work.
 *
 * The player registers itself when this artifact is a dependency, so a builder only calls this with discovery turned
 * off. Without it `preview` returns a player whose state reports the missing artifact. The built-in effect catalogue
 * is registered too. A second call on the same builder does nothing.
 */
public fun FilmstripBuilder.playerBackend(): FilmstripBuilder = install(PlayerRegistrar())

internal class PlayerRegistrar : FilmstripRegistrar {
  override val id: String = "filmstrip-player"

  override fun register(builder: FilmstripBuilder) {
    builder
      .builtInEffects()
      .addPlayerEngineFactory { config, components -> createPlayerEngine(config, components) }
      .addThumbnailSourceFactory { request, components -> createThumbnailSource(request, components) }
  }
}
