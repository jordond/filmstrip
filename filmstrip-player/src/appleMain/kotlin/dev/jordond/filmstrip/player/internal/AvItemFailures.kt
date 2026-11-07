package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackError
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSError

/**
 * Decides which of the attached item's failures the caller still has to hear about.
 *
 * A failed status is terminal for its item, so it is reported once for as long as that item stays attached. A stop
 * short leaves a ready item ready, so it is reported once per play or seek and stands until one of them. A status that
 * fails behind a stop short still standing has nothing new to tell the caller and only moves the status.
 *
 * Engine's dispatcher only.
 *
 * @param attached The item the engine holds now, which a stop short's late hop may no longer be about.
 * @param onFailed Called with the first read of the attached item's failed status.
 * @param onFailedAgain Called with every later read of it, and with a read that follows a stop short still standing.
 * @param onStoppedShort Called with each stop short the caller still has to hear about.
 * @param onResumed Called when a stop short that was standing no longer is.
 */
internal class AvItemFailures(
  private val attached: () -> AVPlayerItem?,
  private val onFailed: (PlaybackError) -> Unit,
  private val onFailedAgain: (PlaybackError) -> Unit,
  private val onStoppedShort: (PlaybackError) -> Unit,
  private val onResumed: () -> Unit,
) {
  private var failedItem: AVPlayerItem? = null

  /**
   * What the attached item stopped short with, while no play or seek has moved it on.
   */
  var stoppedShortError: PlaybackError? = null
    private set

  /**
   * Answers a read of [target]'s failed status.
   */
  fun statusFailed(target: AVPlayerItem) {
    val error = target.playbackError()
    val reported = failedItem === target || stoppedShortError != null
    failedItem = target
    if (reported) onFailedAgain(error) else onFailed(error)
  }

  /**
   * Answers [stopped] saying it stopped before its end, with the error its notification carried.
   */
  fun stoppedShort(
    stopped: AVPlayerItem,
    reason: NSError?,
  ) {
    if (stopped !== attached() || stopped === failedItem || stoppedShortError != null) return
    val error = stopped.stoppedShortError(reason)
    stoppedShortError = error
    onStoppedShort(error)
  }

  /**
   * Lets the next stop short report, once a play, a seek or new parameters have moved the item on from the last one.
   */
  fun resumed() {
    if (stoppedShortError == null) return
    stoppedShortError = null
    onResumed()
  }

  /**
   * Forgets everything, for an item that is no longer attached.
   */
  fun clear() {
    failedItem = null
    stoppedShortError = null
  }
}
