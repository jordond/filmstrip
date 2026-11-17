package dev.jordond.filmstrip.player.internal

import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine driving [player], or null when filmstrip did not build it.
 *
 * Engines register on construction and drop out on release, so a surface holding only the platform
 * player from [dev.jordond.filmstrip.player.VideoPlayer.nativePlayer] can find the engine behind
 * it. Registered on the player's thread and read from whichever thread a surface attaches on.
 */
@androidx.annotation.OptIn(ExperimentalApi::class)
internal fun engineFor(player: CompositionPlayer): Media3PlayerEngine? = engines[player]

internal val engines = ConcurrentHashMap<CompositionPlayer, Media3PlayerEngine>()
