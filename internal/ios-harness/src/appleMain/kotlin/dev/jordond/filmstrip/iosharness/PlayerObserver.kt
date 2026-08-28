package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatus
import platform.AVFoundation.AVPlayerStatus
import platform.AVFoundation.AVPlayerTimeControlStatus
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import platform.objc.class_addMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/**
 * A snapshot of everything the player state model observes.
 */
data class PlayerState(
  val playerStatus: AVPlayerStatus,
  val timeControlStatus: AVPlayerTimeControlStatus,
  val itemStatus: AVPlayerItemStatus?,
  val likelyToKeepUp: Boolean?,
)

/**
 * The KVO glue, built the way Kotlin/Native actually allows.
 *
 * The obvious shape is a hand-written `NSObject` subclass overriding
 * `observeValueForKeyPath:ofObject:change:context:`. That is not expressible in Kotlin/Native
 * 2.4.10, and both halves of the objection were measured rather than reasoned about:
 *
 * - cinterop exposes the callback as `public final external fun NSObject.observeValueForKeyPath(...)`
 *   which is a final extension function. Declaring it with `override` fails to compile with
 *   "'observeValueForKeyPath' overrides nothing".
 * - Declaring it as a plain member with matching parameter names compiles, but Kotlin/Native does
 *   not export it to the Objective-C class, so at runtime KVO aborts the process with
 *   "An -observeValueForKeyPath:ofObject:change:context: message was received but not handled".
 *
 * What works is installing the selector on the generated class ourselves, once, through the
 * Objective-C runtime. The IMP does not decode any of its arguments: it recovers the owning
 * observer from `self` and re-reads the properties. That is deliberate. Decoding an `NSString`
 * key path from a raw pointer needs interop that is not public API, and a state model wants an
 * idempotent snapshot rather than a stream of deltas anyway.
 *
 * The two sharp edges are implemented, not described:
 *
 * 1. **Lifetime.** The observer owns the observed [AVPlayerItem]. [replaceItem] and [dispose] are
 *    the only ways an item is let go and both unregister first, so no path releases an observed
 *    item, and the process-terminating crash that would cause cannot be reached from this API.
 * 2. **Threading.** Notifications arrive on whatever thread caused the change and can be
 *    re-entrant. One that lands while a transition is being applied is queued and drained after
 *    that transition commits, never applied inside it.
 */
@OptIn(ExperimentalForeignApi::class)
class PlayerObserver(
  private val player: AVPlayer,
  private val onState: (PlayerState) -> Unit,
) : NSObject() {
  private var observedItem: AVPlayerItem? = null
  private var applying = false
  private var queuedNotifications = 0

  /**
   * Number of KVO notifications that reached Kotlin. Harness evidence, not production surface.
   */
  var notifications: Int = 0
    private set

  init {
    installKvoSelectorOnce(this)
    registry[objcPtr().toLong()] = this
    player.addObserver(this, forKeyPath = PLAYER_STATUS, options = OPTIONS, context = null)
    player.addObserver(this, forKeyPath = TIME_CONTROL_STATUS, options = OPTIONS, context = null)
  }

  /**
   * Swaps the observed item, unregistering the previous one first.
   *
   * The order is the lifetime rule: an item that deallocates while still observed terminates the
   * process, so nothing is released before its observers are removed.
   */
  fun replaceItem(item: AVPlayerItem?) {
    detach()
    if (item != null) {
      item.addObserver(this, forKeyPath = ITEM_STATUS, options = OPTIONS, context = null)
      item.addObserver(this, forKeyPath = LIKELY_TO_KEEP_UP, options = OPTIONS, context = null)
    }
    observedItem = item
    player.replaceCurrentItemWithPlayerItem(item)
  }

  fun dispose() {
    detach()
    player.removeObserver(this, forKeyPath = PLAYER_STATUS)
    player.removeObserver(this, forKeyPath = TIME_CONTROL_STATUS)
    registry.remove(objcPtr().toLong())
  }

  fun snapshot(): PlayerState =
    PlayerState(
      playerStatus = player.status,
      timeControlStatus = player.timeControlStatus,
      itemStatus = observedItem?.status,
      likelyToKeepUp = observedItem?.playbackLikelyToKeepUp,
    )

  private fun detach() {
    val item = observedItem ?: return
    observedItem = null
    item.removeObserver(this, forKeyPath = ITEM_STATUS)
    item.removeObserver(this, forKeyPath = LIKELY_TO_KEEP_UP)
  }

  /**
   * Queues re-entrant notifications instead of applying them inside the current transition.
   */
  internal fun notifyChanged() {
    notifications++
    if (applying) {
      queuedNotifications++
      return
    }
    applying = true
    try {
      onState(snapshot())
      while (queuedNotifications > 0) {
        queuedNotifications--
        onState(snapshot())
      }
    } finally {
      applying = false
    }
  }
}

// Top level rather than in a companion: a subclass of an Objective-C type cannot carry companion
// fields, which the compiler rejects outright.
const val PLAYER_STATUS: String = "status"
const val TIME_CONTROL_STATUS: String = "timeControlStatus"
const val ITEM_STATUS: String = "status"
const val LIKELY_TO_KEEP_UP: String = "playbackLikelyToKeepUp"

@OptIn(ExperimentalForeignApi::class)
private val OPTIONS = NSKeyValueObservingOptionNew or NSKeyValueObservingOptionInitial

/**
 * Raw `self` pointer to owner, so the IMP can stay a non-capturing static function.
 */
private val registry = mutableMapOf<Long, PlayerObserver>()

private var selectorInstalled = false

/**
 * Adds `observeValueForKeyPath:ofObject:change:context:` to the class Kotlin/Native generated for
 * [PlayerObserver].
 *
 * Once per class, not per instance: Objective-C methods live on classes. The encoding `v@:@@@^v` is
 * void return, then the implicit self and selector, then the three object arguments and the raw
 * context pointer.
 */
@OptIn(ExperimentalForeignApi::class)
private fun installKvoSelectorOnce(instance: NSObject) {
  if (selectorInstalled) return
  selectorInstalled = true

  // Nothing is decoded from the arguments. The owner re-reads the properties instead, which avoids
  // non-public pointer interop and gives the state model a snapshot rather than a delta. The six
  // parameters are self, _cmd, keyPath, object, change and context.
  val implementation =
    staticCFunction<
      COpaquePointer?,
      COpaquePointer?,
      COpaquePointer?,
      COpaquePointer?,
      COpaquePointer?,
      COpaquePointer?,
      Unit,
    > { self, _, _, _, _, _ ->
      self?.let { registry[it.rawValue.toLong()]?.notifyChanged() }
    }

  val added =
    class_addMethod(
      object_getClass(instance),
      sel_registerName("observeValueForKeyPath:ofObject:change:context:"),
      implementation.reinterpret(),
      "v@:@@@^v",
    )
  check(added) { "could not install the KVO selector on the generated Objective-C class" }
}
