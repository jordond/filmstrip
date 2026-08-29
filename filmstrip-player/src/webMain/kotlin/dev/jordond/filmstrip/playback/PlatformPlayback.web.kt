package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource

// Both factories decline until the browser playback backend lands, so `preview` returns a player
// reporting the missing backend by name.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: ComponentRegistry,
): PlayerEngine? = null

internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: ComponentRegistry,
): ThumbnailSource? = null
