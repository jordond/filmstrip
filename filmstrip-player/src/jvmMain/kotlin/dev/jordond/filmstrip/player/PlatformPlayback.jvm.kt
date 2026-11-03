package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.FfmpegThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.FfmpegThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

// One serialised worker, off the main dispatcher. Everything the engine waits on is a process or
// a pipe, and a desktop host's main thread is where its window is being drawn.
@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun createPlayerEngine(
  config: PlayerConfig,
  components: FilmstripComponents,
): PlayerEngine? =
  FfmpegPlayerEngine(
    parent = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob()),
    planner = FfmpegPreviewPlanner(components),
    config = config,
  )

// One serialised worker of its own, so a strip filling in the background never queues behind the
// transport's pump or delays it.
internal actual fun createThumbnailSource(
  request: ThumbnailRequest,
  components: FilmstripComponents,
): ThumbnailSource? =
  FfmpegThumbnailSource(
    scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob()),
    planner = FfmpegThumbnailPlanner(components),
  )
