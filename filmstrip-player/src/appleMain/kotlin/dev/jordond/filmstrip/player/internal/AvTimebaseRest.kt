package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRetain
import platform.CoreMedia.CMTimebaseGetRate
import platform.CoreMedia.CMTimebaseGetTime
import platform.CoreMedia.CMTimebaseRef
import platform.CoreMedia.kCMTimebaseNotification_EffectiveRateChanged
import platform.Foundation.CFBridgingRelease
import kotlin.time.Duration

/**
 * Tells the transport where the item's clock came to rest once playback stopped.
 *
 * The transport calls [await] when it stops, and hears back once the clock has stopped moving.
 */
internal interface ClockRest {
  /**
   * Calls [onRest] on the engine's dispatcher with where the clock rests, once its rate is zero. A wait already
   * running is replaced.
   */
  fun await(onRest: (Duration) -> Unit)

  /**
   * Drops the wait running, if any, so its [await] callback never runs.
   */
  fun cancel()
}

/**
 * Reads where the item's timebase rests after a pause.
 *
 * AVFoundation's limit: `pause()` returns while the timebase still runs at the old rate, and its clock moves on until
 * the rate reaches zero a moment later. Measured on the macOS host, the rate still read 1.0 about 170 us after
 * `pause()` and the clock moved another 50 us. The timebase posts its effective rate change on a thread of its own once
 * it has stopped, and the time it reads from then on is where playback stopped.
 *
 * Engine's dispatcher only, apart from the notification, which hops onto [scope] before it reads anything.
 *
 * @param scope The engine's scope, on the dispatcher everything is confined to.
 * @param timebase The timebase of the item attached now, or null with no item.
 * @param itemTime Reads the item's clock, or zero with no item.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvTimebaseRest(
  private val scope: CoroutineScope,
  private val timebase: () -> CMTimebaseRef?,
  private val itemTime: () -> Duration,
) : ClockRest {
  private var onRest: ((Duration) -> Unit)? = null
  private var watched: CMTimebaseRef? = null
  private var observer: AvNotificationObserver? = null

  override fun await(onRest: (Duration) -> Unit) {
    cancel()
    val clock = timebase()
    if (clock == null) {
      onRest(itemTime())
      return
    }
    this.onRest = onRest
    watched = clock
    // Watched before the rate is read, so a stop landing between the two still posts to it.
    observer =
      AvNotificationObserver(RATE_CHANGED, CFBridgingRelease(CFRetain(clock))) {
        scope.launch { check(clock) }
      }
    check(clock)
  }

  override fun cancel() {
    observer?.dispose()
    observer = null
    watched = null
    onRest = null
  }

  private fun check(clock: CMTimebaseRef) {
    if (clock != watched || CMTimebaseGetRate(clock) != 0.0) return
    val rest = onRest ?: return
    val at = CMTimebaseGetTime(clock).toDuration()
    cancel()
    rest(at)
  }

  private companion object {
    val RATE_CHANGED: String = CFBridgingRelease(CFRetain(kCMTimebaseNotification_EffectiveRateChanged)) as String
  }
}
