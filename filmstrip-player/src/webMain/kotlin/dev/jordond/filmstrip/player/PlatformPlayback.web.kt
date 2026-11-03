package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.BrowserThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.BrowserThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// A page has one thread, and the compositor, the decoders and the audio graph all live on it, so
// the engine is confined to the main dispatcher.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: FilmstripComponents,
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
  components: FilmstripComponents,
): ThumbnailSource? =
  BrowserThumbnailSource(
    scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    planner = BrowserThumbnailPlanner(components),
  )
