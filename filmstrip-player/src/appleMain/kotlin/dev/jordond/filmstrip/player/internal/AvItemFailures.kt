package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackError
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSError

/**
 * Decides which of the attached item's failures the caller still has to hear about.
 *
 * A failed status is terminal for its item, so it is reported once for as long as that item stays attached. A stop
 * short leaves a ready item ready, so it is reported once per play or seek. A status that fails behind a stop short
 * still standing has nothing new to tell the caller and only moves the status.
 *
 * Engine's dispatcher only.
 *
 * @param attached The item the engine holds now, which a stop short's late hop may no longer be about.
 * @param onFailed Called with the first read of the attached item's failed status.
 * @param onFailedAgain Called with every later read of it, and with a read that follows a stop short still standing.
 * @param onStoppedShort Called with each stop short the caller still has to hear about.
 */
internal class AvItemFailures(
  private val attached: () -> AVPlayerItem?,
  private val onFailed: (PlaybackError) -> Unit,
  private val onFailedAgain: (PlaybackError) -> Unit,
  private val onStoppedShort: (PlaybackError) -> Unit,
) {
  private var failedItem: AVPlayerItem? = null
  private var stoppedShort = false

  /**
   * Answers a read of [target]'s failed status.
   */
  fun statusFailed(target: AVPlayerItem) {
    val error = target.playbackError()
    val reported = failedItem === target || stoppedShort
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
    if (stopped !== attached() || stopped === failedItem || stoppedShort) return
    stoppedShort = true
    onStoppedShort(stopped.stoppedShortError(reason))
  }

  /**
   * Lets the next stop short report, once a play or a seek has moved the item on from the last one.
   */
  fun resumed() {
    stoppedShort = false
  }

  /**
   * Forgets everything, for an item that is no longer attached.
   */
  fun clear() {
    failedItem = null
    stoppedShort = false
  }
}
