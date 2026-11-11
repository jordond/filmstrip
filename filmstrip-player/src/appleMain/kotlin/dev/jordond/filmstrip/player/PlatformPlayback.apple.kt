package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.MonotonicPreviewClock
import dev.jordond.filmstrip.player.internal.thumbnail.AvThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.AvThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// The engine and everything it observes are confined to one dispatcher, and the main one is where a
// host's surface already is.
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: FilmstripComponents,
): PlayerEngine? =
  AvPlayerEngine(
    parent = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    planner = AvPreviewPlanner(components, MonotonicPreviewClock()),
    config = config,
  )

// Off the main dispatcher, unlike the engine: a strip renders while the host is scrolling it and
// the frames it wants are not the ones on screen.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: FilmstripComponents,
): ThumbnailSource? =
  AvThumbnailSource(
    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    planner = AvThumbnailPlanner(components),
  )
