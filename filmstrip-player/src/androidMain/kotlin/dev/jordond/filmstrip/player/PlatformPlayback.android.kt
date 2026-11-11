package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.MissingContextPlayerEngine
import dev.jordond.filmstrip.player.internal.MissingContextThumbnailSource
import dev.jordond.filmstrip.player.internal.MonotonicPreviewClock
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Everything a listener sees is confined to one dispatcher, and the main one is where a host's
// surface already is. The player keeps a looper of its own behind that, because media3 confines
// every call and callback to one. Without a Context the engine built fails every load with
// MISSING_CONTEXT, so a host that disabled App Startup and never called Filmstrip(context) hears
// about the missing install rather than about a missing backend.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: FilmstripComponents,
): PlayerEngine? {
  val parent = CoroutineScope(Dispatchers.Main + SupervisorJob())
  val context = FilmstripContext.get() ?: return MissingContextPlayerEngine(parent)
  return Media3PlayerEngine(
    parent = parent,
    context = context,
    planner = Media3PreviewPlanner(components, MonotonicPreviewClock()),
    config = config,
  )
}

// Fails every request with MISSING_CONTEXT without a Context, for the same reason the engine does.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: FilmstripComponents,
): ThumbnailSource? {
  val context = FilmstripContext.get() ?: return MissingContextThumbnailSource
  return Media3ThumbnailSource(
    scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    context = context,
    planner = Media3ThumbnailPlanner(components),
  )
}
