package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource

// Both factories decline until the Android playback backend lands, so `preview` returns a player
// reporting the missing backend by name.
internal actual fun createPlayerEngine(config: PlayerConfig): PlayerEngine? = null

internal actual fun createThumbnailSource(request: ThumbnailRequest): ThumbnailSource? = null
