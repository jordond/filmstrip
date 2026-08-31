package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailPlanner
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailSource
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Everything a listener sees is confined to one dispatcher, and the main one is where a host's
// surface already is. The player keeps a looper of its own behind that, because media3 confines
// every call and callback to one. Declining without a Context is what makes a host that disabled
// App Startup and never called Filmstrip(context) read as a missing backend rather than a crash.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: ComponentRegistry,
): PlayerEngine? {
  val context = FilmstripContext.get() ?: return null
  return Media3PlayerEngine(
    parent = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    context = context,
    planner = Media3PreviewPlanner(components),
    config = config,
  )
}

// Declines without a Context for the same reason the engine does: a host that disabled App Startup
// and never called Filmstrip(context) reads as a missing backend rather than as a crash.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: ComponentRegistry,
): ThumbnailSource? {
  val context = FilmstripContext.get() ?: return null
  return Media3ThumbnailSource(
    scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    context = context,
    planner = Media3ThumbnailPlanner(components),
  )
}
