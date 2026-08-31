package dev.jordond.filmstrip.playback.internal

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSLock
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.objc.class_addMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/**
 * The key-value half of the Apple engine's state, as one observer over the player, its item and the
 * layer a surface is drawing into.
 *
 * One observer rather than one per property. Nothing is decoded from the notification: [onChanged]
 * is told only that something moved, and the engine re-reads every property it cares about. That
 * keeps the state machine idempotent under the extra notifications a replacement delivers, and it
 * avoids reading an `NSString` key path out of a raw pointer, which needs interop that is not
 * public API.
 *
 * Registration is not symmetrical with deallocation here. `removeObserver` on a pair that was never
 * registered raises, and an observed object that deallocates while still observed terminates the
 * process, so [observeItem] and [dispose] are the only ways an item is let go and both unregister
 * first.
 *
 * @param onChanged Called on whichever thread caused the change. It must hop to the engine's
 *   dispatcher itself, and it must not throw: this runs inside an Objective-C frame.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvKeyValueObserver(
  private val onChanged: () -> Unit,
) : NSObject() {
  private var player: AVPlayer? = null
  private var item: AVPlayerItem? = null
  private var layer: AVPlayerLayer? = null

  init {
    installObserveSelector(this)
    registerObserver(objcPtr().toLong(), this)
  }

  /**
   * Starts watching [player]'s transport, which is what tells playing from paused.
   */
  fun observePlayer(player: AVPlayer) {
    if (this.player != null) return
    this.player = player
    PLAYER_KEYS.forEach { key -> player.addObserver(this, forKeyPath = key, options = OPTIONS, context = null) }
  }

  /**
   * Swaps the observed item, unregistering the previous one first.
   */
  fun observeItem(item: AVPlayerItem?) {
    detachItem()
    if (item != null) {
      ITEM_KEYS.forEach { key -> item.addObserver(this, forKeyPath = key, options = OPTIONS, context = null) }
    }
    this.item = item
  }

  /**
   * Swaps the observed preview layer, unregistering the previous one first.
   */
  fun observeLayer(layer: AVPlayerLayer?) {
    detachLayer()
    if (layer != null) {
      LAYER_KEYS.forEach { key -> layer.addObserver(this, forKeyPath = key, options = OPTIONS, context = null) }
    }
    this.layer = layer
  }

  /**
   * Unregisters everything. Idempotent, and never left to a finalizer.
   */
  fun dispose() {
    detachItem()
    detachLayer()
    player?.let { player -> PLAYER_KEYS.forEach { key -> player.removeObserver(this, forKeyPath = key) } }
    player = null
    registerObserver(objcPtr().toLong(), null)
  }

  private fun detachItem() {
    val observed = item ?: return
    item = null
    ITEM_KEYS.forEach { key -> observed.removeObserver(this, forKeyPath = key) }
  }

  private fun detachLayer() {
    val observed = layer ?: return
    layer = null
    LAYER_KEYS.forEach { key -> observed.removeObserver(this, forKeyPath = key) }
  }

  /**
   * Delivers one notification, swallowing anything [onChanged] throws.
   *
   * AVFoundation calls this from its own queue, and an exception crossing back into Objective-C
   * terminates the process rather than reaching a caller.
   */
  internal fun changed() {
    try {
      onChanged()
    } catch (
      @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
    ) {
      // Nothing here can report, and the engine re-reads every property on the next notification.
    }
  }
}

/**
 * Watches one `NSNotificationCenter` name and hands each posting to [onPosted].
 *
 * The block runs on whichever thread posted, so [onPosted] hops to the engine's dispatcher itself.
 * Anything it throws is swallowed, for the same reason [AvKeyValueObserver.changed] swallows.
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
      try {
        notification?.let(onPosted)
      } catch (
        @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
      ) {
        // Nothing here can report, and a missed edge is recoverable. A thrown one is not.
      }
    }

  /**
   * Stops watching. Idempotent.
   */
  fun dispose() {
    token?.let { center.removeObserver(it) }
    token = null
  }
}

/**
 * The player properties the engine reads.
 *
 * `timeControlStatus` alone separates playing from paused from waiting;
 * `reasonForWaitingToPlay` is read off the player when it moves rather than observed separately.
 */
private val PLAYER_KEYS = listOf("timeControlStatus")

/**
 * The item properties the engine reads: whether it is presentable, and whether data is arriving.
 */
private val ITEM_KEYS = listOf("status", "playbackLikelyToKeepUp", "playbackBufferEmpty")

/**
 * The layer property that says the surface has pixels rather than an empty rectangle.
 */
private val LAYER_KEYS = listOf("readyForDisplay")

@OptIn(ExperimentalForeignApi::class)
private val OPTIONS = NSKeyValueObservingOptionNew

/**
 * Raw `self` pointer to owner, so the installed implementation can stay non-capturing.
 *
 * Process-wide, and reached from whichever thread key-value observing delivers on, so every read
 * and write of it goes through [registryLock]. Engines are built off the main thread and more than
 * one host can build one at a time.
 */
private val observers = mutableMapOf<Long, AvKeyValueObserver>()

private var selectorInstalled = false

/**
 * Guards [observers] and [selectorInstalled].
 *
 * Held only across a map operation and the one-time selector install, never across [onChanged],
 * which runs after the lookup has let go.
 */
private val registryLock = NSLock()

/**
 * Records [observer] against [key], or forgets [key] when it is null.
 */
private fun registerObserver(
  key: Long,
  observer: AvKeyValueObserver?,
) {
  registryLock.lock()
  try {
    if (observer == null) observers.remove(key) else observers[key] = observer
  } finally {
    registryLock.unlock()
  }
}

/**
 * The observer registered against [key], or null once it has been disposed.
 */
private fun observerFor(key: Long): AvKeyValueObserver? {
  registryLock.lock()
  try {
    return observers[key]
  } finally {
    registryLock.unlock()
  }
}

/**
 * Adds `observeValueForKeyPath:ofObject:change:context:` to the class Kotlin/Native generated for
 * [AvKeyValueObserver].
 *
 * cinterop exposes that callback as a final extension function on `NSObject`, so a subclass cannot
 * override it, and a plain member of the same name is not exported to the generated Objective-C
 * class, which makes KVO abort the process with an unhandled-selector message. Installing the
 * selector through the Objective-C runtime is what actually answers. The encoding `v@:@@@^v` is a
 * void return, the implicit self and selector, three object arguments and the raw context pointer.
 *
 * Once per class rather than per instance, because Objective-C methods live on classes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun installObserveSelector(instance: NSObject) {
  // Held across the install rather than around the flag alone. An observer registered on another
  // thread between the flag going up and the method landing would be delivered to a class that
  // still does not answer the selector, which aborts the process.
  registryLock.lock()
  try {
    if (selectorInstalled) return

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
        // The lock is let go inside observerFor, so a change handler that builds or disposes
        // another observer is not waiting on this thread.
        self?.let { observerFor(it.rawValue.toLong())?.changed() }
      }

    val added =
      class_addMethod(
        object_getClass(instance),
        sel_registerName("observeValueForKeyPath:ofObject:change:context:"),
        implementation.reinterpret(),
        "v@:@@@^v",
      )
    check(added) { "could not install the key-value observing selector on the generated class" }
    selectorInstalled = true
  } finally {
    registryLock.unlock()
  }
}
