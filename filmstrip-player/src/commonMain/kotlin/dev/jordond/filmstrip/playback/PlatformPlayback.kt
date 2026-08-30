package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
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
  components: ComponentRegistry,
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
  components: ComponentRegistry,
): ThumbnailSource?
