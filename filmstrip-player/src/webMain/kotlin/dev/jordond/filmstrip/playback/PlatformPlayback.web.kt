package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.playback.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.playback.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.playback.internal.BrowserThumbnailPlanner
import dev.jordond.filmstrip.playback.internal.BrowserThumbnailSource
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// A page has one thread, and the compositor, the decoders and the audio graph all live on it, so
// the engine is confined to the main dispatcher rather than given one of its own.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: ComponentRegistry,
): PlayerEngine? =
  BrowserPlayerEngine(
    parent = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    planner = BrowserPreviewPlanner(components),
    config = config,
  )

// The same dispatcher as the engine, for the same reason: the compositor and the decoders a strip
// frame goes through live on the page's one thread.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: ComponentRegistry,
): ThumbnailSource? =
  BrowserThumbnailSource(
    scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    planner = BrowserThumbnailPlanner(components),
  )
