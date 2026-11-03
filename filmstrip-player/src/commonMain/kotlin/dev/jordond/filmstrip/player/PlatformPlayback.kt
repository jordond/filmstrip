package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource

/**
 * Builds the platform's playback engine, or returns null when it cannot run here.
 *
 * [components] holds the resolvers and probers the owning `Filmstrip` was built with, the same set
 * an export runs through.
 */
internal expect fun createPlayerEngine(
  config: PlayerConfig,
  components: FilmstripComponents,
): PlayerEngine?

/**
 * Builds a thumbnail source that applies the composition's effects, or returns null when it cannot
 * run here.
 *
 * [components] holds the resolvers and probers the owning `Filmstrip` was built with, the same set
 * an export runs through.
 */
internal expect fun createThumbnailSource(
  request: ThumbnailRequest,
  components: FilmstripComponents,
): ThumbnailSource?
