package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerWaitingToMinimizeStallsReason
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.timeControlStatus
import platform.Foundation.NSError
import platform.Foundation.NSUnderlyingErrorKey

/**
 * Watches the player, its item and the layer a surface draws into, and tells the engine what moved.
 *
 * Key-value changes, the item's end, a failure to reach it and a stall each arrive on whichever thread AVFoundation
 * raised them on, and each hops onto [scope] before its callback runs. The first-frame edge is read on the engine's
 * dispatcher, through [reportFirstFrame].
 *
 * @param scope The engine's scope, on the dispatcher every callback runs on.
 * @param player The player whose transport is watched.
 * @param onChanged Called when any watched property moves.
 * @param onReachedEnd Called when the item plays to its end.
 * @param onFailedToPlayToEnd Called with the item that stopped before its end and the error its notification carried.
 * @param onStalled Called when the item runs out of data.
 * @param onFirstFrame Called once per item, on the edge where an attached layer starts showing it.
 */
internal class AvItemObservers(
  private val scope: CoroutineScope,
  private val player: AVPlayer,
  onChanged: () -> Unit,
  private val onReachedEnd: () -> Unit,
  private val onFailedToPlayToEnd: (AVPlayerItem, NSError?) -> Unit,
  private val onStalled: () -> Unit,
  private val onFirstFrame: () -> Unit,
) {
  private val observer = AvKeyValueObserver { scope.launch { onChanged() } }
  private var notifications: List<AvNotificationObserver> = emptyList()
  private var layerReady = false
  private var firstFrameEmitted = false

  /**
   * The layer a surface is drawing into, or null when none is attached.
   */
  var surfaceLayer: AVPlayerLayer? = null
    private set

  /**
   * Starts watching the player's transport, which is what tells playing from paused.
   */
  fun observePlayer() {
    observer.observePlayer(player)
  }

  /**
   * Starts watching [item], which has not been shown yet.
   */
  fun observeItem(item: AVPlayerItem) {
    layerReady = false
    firstFrameEmitted = false
    observer.observeItem(item)
    notifications =
      listOf(
        AvNotificationObserver(AVPlayerItemDidPlayToEndTimeNotification, item) { scope.launch { onReachedEnd() } },
        AvNotificationObserver(AVPlayerItemFailedToPlayToEndTimeNotification, item) { notification ->
          val reason = notification.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
          scope.launch { onFailedToPlayToEnd(item, reason) }
        },
        AvNotificationObserver(AVPlayerItemPlaybackStalledNotification, item) { scope.launch { onStalled() } },
      )
  }

  /**
   * Stops watching the item, unregistering everything first.
   */
  fun detachItem() {
    notifications.forEach { it.dispose() }
    notifications = emptyList()
    observer.observeItem(null)
  }

  /**
   * Swaps the watched layer for [layer], which has not shown anything yet.
   */
  fun observeLayer(layer: AVPlayerLayer?) {
    surfaceLayer = layer
    layerReady = false
    observer.observeLayer(layer)
  }

  /**
   * Calls [onFirstFrame] on the edge where an attached layer starts showing [target]'s pixels, once per item.
   *
   * Readiness is held against the item's own status, so a layer still showing the previous composition while a fresh
   * item prepares does not count as the new one arriving.
   */
  fun reportFirstFrame(target: AVPlayerItem) {
    val showing = surfaceLayer?.readyForDisplay == true && target.status == AVPlayerItemStatusReadyToPlay
    val became = showing && !layerReady
    layerReady = showing
    if (!became || firstFrameEmitted) return

    firstFrameEmitted = true
    onFirstFrame()
  }

  /**
   * Drops the layer and stops watching everything. Detach the item first.
   */
  fun dispose() {
    surfaceLayer = null
    observer.dispose()
  }
}

/**
 * The failure this item reports, read as a [PlaybackError].
 *
 * An item that names no error reads as a failure with no platform code.
 */
internal fun AVPlayerItem.playbackError(): PlaybackError = error.toPlaybackError()

/**
 * The failure this item reports for stopping before its end, read as a [PlaybackError].
 *
 * The item's own error is read first. An item that names none yet reads as [reason], which its notification carried.
 */
internal fun AVPlayerItem.stoppedShortError(reason: NSError?): PlaybackError = (error ?: reason).toPlaybackError()

/**
 * This error read as a [PlaybackError].
 *
 * The platform code is this error's own. The domain and code of the error under it, which is where AVFoundation puts
 * the status that says what went wrong, are added to the message.
 */
internal fun NSError?.toPlaybackError(): PlaybackError {
  val underlying = this?.userInfo?.get(NSUnderlyingErrorKey) as? NSError
  val description = this?.localizedDescription ?: "AVFoundation could not play this composition."
  return PlaybackError.Underlying(
    platformCode = this?.code?.toInt() ?: PlaybackError.Underlying.NO_PLATFORM_CODE,
    message = if (underlying == null) description else "$description (${underlying.domain} ${underlying.code})",
  )
}

/**
 * Whether this item cannot advance at the position it is on.
 */
internal fun AVPlayerItem.isStalled(): Boolean = !playbackLikelyToKeepUp || playbackBufferEmpty

/**
 * Whether the player is holding for data rather than for an instruction.
 */
internal fun AVPlayer.isWaitingOnData(): Boolean =
  timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate &&
    reasonForWaitingToPlay == AVPlayerWaitingToMinimizeStallsReason
