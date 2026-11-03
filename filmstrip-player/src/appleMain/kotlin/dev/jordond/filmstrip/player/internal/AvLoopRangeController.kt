package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.addBoundaryTimeObserverForTimes
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.valueWithCMTime
import platform.Foundation.NSValue
import platform.darwin.dispatch_queue_t

/**
 * Holds the loop range and watches the player for playback crossing its wrap.
 *
 * Engine's dispatcher only, apart from the boundary observer AVFoundation calls on [callbackQueue], which hops back
 * onto [scope] before it touches anything.
 *
 * @param scope The engine's scope, on the dispatcher everything is confined to.
 * @param player The player whose clock is watched.
 * @param callbackQueue The queue AVFoundation calls the boundary observer on.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Hears every change to the range.
 * @param onBoundary Called on the engine's dispatcher once playback crosses the wrap.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvLoopRangeController(
  private val scope: CoroutineScope,
  private val player: AVPlayer,
  private val callbackQueue: dispatch_queue_t,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val onBoundary: () -> Unit,
) {
  private var token: Any? = null

  /**
   * The loop range standing now, or null when playback does not loop.
   */
  var range: TimeRange? = null
    private set

  /**
   * Replaces the loop range and re-arms the boundary for it, telling [guard] when the range really moved.
   */
  fun set(range: TimeRange?) {
    if (range != this.range) guard.rangeChanged()
    this.range = range
    arm()
  }

  /**
   * Where the loop range standing now wraps in the composition loaded now, or null when either is missing.
   */
  fun wrap(): LoopWrap? {
    val range = range ?: return null
    val resolved = resolved() ?: return null
    return loopWrapOf(range, resolved.duration, resolved.frameRate)
  }

  /**
   * Watches for playback crossing the loop range's wrap, for a range the composition runs on past.
   *
   * A range the composition ends inside wraps off the item's end instead, since the item stops there. Called again for
   * each composition, whose duration and frame rate move the wrap.
   */
  fun arm() {
    dispose()
    val wrap = wrap()?.takeUnless { it.reachesEnd } ?: return

    token =
      player.addBoundaryTimeObserverForTimes(listOf(NSValue.valueWithCMTime(wrap.at.toCMTime())), callbackQueue) {
        insideObjcFrame {
          // Read again on the engine's dispatcher, so a wrap the playhead already took is not taken twice.
          scope.launch { onBoundary() }
        }
      }
  }

  /**
   * Stops watching the boundary. Safe to call when nothing is armed.
   */
  fun dispose() {
    token?.let { player.removeTimeObserver(it) }
    token = null
  }
}
