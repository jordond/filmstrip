package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Can Kotlin/Native express the KVO glue at all, and does it survive the lifetime hazard?
 *
 * The first question is not rhetorical. `observeValueForKeyPath:ofObject:change:context:` is exposed
 * by cinterop as a final extension function on `NSObject`, so a hand-written `NSObject` subclass
 * overriding it does not compile. What does work is declaring a member
 * with the same name and parameter names and no `override`: Kotlin/Native emits it into the
 * generated Objective-C class under the matching selector, and the runtime dispatches to it. This
 * test is what makes that a fact rather than a hope. If the selector were wrong, no notification
 * would ever arrive and [observed] would stay empty.
 */
@OptIn(ExperimentalForeignApi::class)
class PlayerObserverTest {
  @Test
  fun kvoNotificationsReachKotlin() {
    val states = mutableListOf<PlayerState>()
    val player = AVPlayer()
    val observer = PlayerObserver(player) { states += it }

    try {
      // NSKeyValueObservingOptionInitial fires synchronously from addObserver, so a delivered
      // notification here proves the installed selector matched, with no run loop needed.
      assertTrue(states.size >= 2, "no KVO notification reached Kotlin; got ${states.size}")
      assertEquals(2, observer.notifications, "expected one notification per observed player key")
    } finally {
      observer.dispose()
    }
  }

  @Test
  fun observedItemChangesAreDelivered() {
    val states = mutableListOf<PlayerState>()
    val player = AVPlayer()
    val observer = PlayerObserver(player) { states += it }

    try {
      val before = observer.notifications
      observer.replaceItem(AVPlayerItem(uRL = NSURL(string = "file:///dev/null")))
      // Three, not two: both item keys deliver their Initial notification, and replacing the item
      // also moves the player's own timeControlStatus. Measured rather than assumed. The state
      // model has to be idempotent under exactly this kind of extra notification, which is why the
      // observer re-reads a snapshot instead of applying deltas.
      assertEquals(
        3,
        observer.notifications - before,
        "unexpected notification count for an item replacement",
      )
      assertTrue(states.last().itemStatus != null, "item state was not visible in the snapshot")
    } finally {
      observer.dispose()
    }
  }

  /**
   * The observer survives 100 rapid item replacements.
   *
   * The hazard is an `AVPlayerItem` that deallocates while still observed, which terminates the
   * process.
   *
   * Not run under Address Sanitizer, and it cannot be. Kotlin/Native 2.4.10 accepts
   * `binaryOptions["sanitizer"] = "address"` and then warns "ADDRESS sanitizer is not supported
   * yet". The linked binary contains no `__asan` symbols. Running this under ASan needs the loop
   * driven from the Xcode harness with the sanitizer enabled on the scheme, where ASan instruments
   * the Objective-C side even though the Kotlin framework is uninstrumented.
   *
   * What this test does cover is the failure mode itself: the observer/observed mismatch is enforced
   * by the Objective-C runtime, which raises rather than corrupting memory, so an unregistered-item
   * bug fails this test loudly without a sanitizer.
   */
  @Test
  fun survivesRapidItemReplacement() {
    val player = AVPlayer()
    val observer = PlayerObserver(player) { }
    // Two keys at construction, then three per replacement (both item keys plus the player's
    // timeControlStatus), and one more for the final replaceItem(null).
    val baseline = 2

    try {
      repeat(REPLACEMENTS) { index ->
        observer.replaceItem(AVPlayerItem(uRL = NSURL(string = "file:///tmp/item-$index.mp4")))
      }
      observer.replaceItem(null)

      // Two observed properties per replacement, each delivering its Initial notification. A
      // shortfall would mean an item was released without its observers, which is the crash this
      // test exists to rule out.
      assertEquals(
        baseline + REPLACEMENTS * 3 + 1,
        observer.notifications,
        "expected three notifications per replacement plus one for the final clear",
      )
    } finally {
      observer.dispose()
    }
  }

  private companion object {
    const val REPLACEMENTS = 100
  }
}
