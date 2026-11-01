package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives [PlayerObserver] through many item replacements in a row and checks what reaches its callback.
 *
 * [PlayerObserver] registers with the New and Initial options and dispatches through its own registry and selector IMP.
 * The engine's `AvKeyValueObserver` registers with New only, so the Initial deliveries and the snapshot asserted here
 * belong to this observer alone.
 */
@OptIn(ExperimentalForeignApi::class)
class PlayerObserverTest {
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
    val states = mutableListOf<PlayerState>()
    val player = AVPlayer()
    val observer = PlayerObserver(player) { states += it }
    // Two keys at construction, then three per replacement (both item keys plus the player's
    // timeControlStatus), and one more for the final replaceItem(null).
    val baseline = 2

    try {
      // Initial fires synchronously from addObserver, so these arrive with no run loop.
      assertEquals(baseline, observer.notifications, "expected one Initial notification per player key")

      repeat(REPLACEMENTS) { index ->
        observer.replaceItem(AVPlayerItem(uRL = NSURL(string = "file:///tmp/item-$index.mp4")))
        assertNotNull(states.last().itemStatus, "item status was not visible in the snapshot")
      }
      observer.replaceItem(null)

      // Three notifications per replacement, two Initial ones from the item keys and one from the
      // player's timeControlStatus. A shortfall would mean an item was released without its
      // observers, which is the crash this test exists to rule out.
      assertEquals(
        baseline + REPLACEMENTS * 3 + 1,
        observer.notifications,
        "expected three notifications per replacement plus one for the final clear",
      )
      assertEquals(observer.notifications, states.size, "every notification should reach onState")
      assertNull(states.last().itemStatus, "the cleared item is still in the snapshot")
    } finally {
      observer.dispose()
    }
  }

  private companion object {
    const val REPLACEMENTS = 100
  }
}
