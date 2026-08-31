package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.AvPreviewPlanner
import dev.jordond.filmstrip.playback.internal.AvThumbnailPlanner
import dev.jordond.filmstrip.playback.internal.AvThumbnailSource
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// The engine and everything it observes are confined to one dispatcher, and the main one is where a
// host's surface already is.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: ComponentRegistry,
): PlayerEngine? =
  AvPlayerEngine(
    parent = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    planner = AvPreviewPlanner(components),
    config = config,
  )

// Off the main dispatcher, unlike the engine: a strip renders while the host is scrolling it and
// the frames it wants are not the ones on screen.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: ComponentRegistry,
): ThumbnailSource? =
  AvThumbnailSource(
    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    planner = AvThumbnailPlanner(components),
  )
