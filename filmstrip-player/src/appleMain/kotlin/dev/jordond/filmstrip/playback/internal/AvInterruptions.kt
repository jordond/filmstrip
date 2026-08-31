package dev.jordond.filmstrip.playback.internal

import platform.AVFoundation.AVPlayer

/**
 * Watches the occasions outside filmstrip that take playback away.
 *
 * What is reachable differs by platform and is named in each implementation. Two occasions are
 * reachable on neither: a telephony call needs real telephony, and a media services restart needs
 * the system daemon to die, so nothing here claims either.
 *
 * The engine never calls `AVAudioSession.setActive` or `setCategory`. Those are process wide and
 * belong to the host app, which may have configured a session filmstrip knows nothing about. A
 * session carrying `mixWithOthers` is mixable, and a mixable session is never interrupted, so a
 * host that sets it gets no audio focus occasion at all.
 *
 * @param player The player whose own rate changes are watched. The system scopes a rate change to
 *   the player that posted it, and an observer reads one only by registering for that same object.
 * @param onInterrupted Called on whichever thread the system raised the occasion on. It hops to the
 *   engine's dispatcher itself.
 */
internal expect class AvInterruptions(
  player: AVPlayer,
  onInterrupted: () -> Unit,
) {
  /**
   * Stops watching. Idempotent, and never left to a finalizer.
   */
  fun dispose()
}
