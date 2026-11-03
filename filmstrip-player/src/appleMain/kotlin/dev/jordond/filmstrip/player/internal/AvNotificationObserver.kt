package dev.jordond.filmstrip.player.internal

import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.removeObserver
import platform.darwin.NSObjectProtocol

/**
 * Watches one `NSNotificationCenter` name and hands each posting to [onPosted].
 *
 * The block runs on whichever thread posted, so [onPosted] hops to the engine's dispatcher itself. It runs through
 * [insideObjcFrame], since a missed edge is recoverable and a thrown one is not.
 *
 * @param name The notification to watch.
 * @param from The object to watch it on, or null for every poster.
 * @param onPosted Receives each posting.
 */
internal class AvNotificationObserver(
  name: String?,
  from: Any?,
  onPosted: (NSNotification) -> Unit,
) {
  private val center = NSNotificationCenter.defaultCenter

  private var token: NSObjectProtocol? =
    center.addObserverForName(name = name, `object` = from, queue = null) { notification ->
      insideObjcFrame { notification?.let(onPosted) }
    }

  /**
   * Stops watching. Idempotent.
   */
  fun dispose() {
    token?.let { center.removeObserver(it) }
    token = null
  }
}
