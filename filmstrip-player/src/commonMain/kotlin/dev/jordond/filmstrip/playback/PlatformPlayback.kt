package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailSource

/**
 * Builds the platform's playback engine, or returns null when it cannot run here.
 */
internal expect fun createPlayerEngine(
  context: PlatformContext,
  config: PlayerConfig,
): PlayerEngine?

/**
 * Builds a thumbnail source that applies the composition's effects, or returns null when it cannot
 * run here.
 */
internal expect fun createThumbnailSource(request: ThumbnailRequest): ThumbnailSource?
