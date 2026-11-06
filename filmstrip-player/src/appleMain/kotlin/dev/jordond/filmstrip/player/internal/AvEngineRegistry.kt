package dev.jordond.filmstrip.player.internal

import platform.AVFoundation.AVPlayer
import platform.Foundation.NSLock

/**
 * The engine driving [player], or null when filmstrip did not build it.
 *
 * Engines register on construction and drop out on release, so a surface holding only the platform
 * player from [dev.jordond.filmstrip.player.VideoPlayer.nativePlayer] can find the engine behind
 * it.
 */
internal fun engineFor(player: AVPlayer): AvPlayerEngine? = enginesLock.guarded { engines[player] }

/**
 * Process-wide, written from whichever thread built or released an engine and read on the main
 * thread by a surface attaching, so every access goes through [enginesLock].
 */
private val engines = mutableMapOf<AVPlayer, AvPlayerEngine>()

private val enginesLock = NSLock()

/**
 * Records the engine driving [player], or forgets [player] when [engine] is null.
 */
internal fun registerEngine(
  player: AVPlayer,
  engine: AvPlayerEngine?,
) {
  enginesLock.guarded { if (engine == null) engines.remove(player) else engines[player] = engine }
}

private inline fun <T> NSLock.guarded(block: () -> T): T {
  lock()
  try {
    return block()
  } finally {
    unlock()
  }
}
