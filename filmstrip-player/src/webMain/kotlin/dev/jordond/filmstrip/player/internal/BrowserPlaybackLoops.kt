package dev.jordond.filmstrip.player.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The two loops the browser engine runs while playback does, the transport tick and the render pump.
 *
 * The tick runs on a fixed delay nothing in the page can throttle and calls [onTick] for as long as [isPlaying] holds.
 * The pump runs off the page's frame callback and calls [onFrame] once per painted frame, until [isPlaying] stops
 * holding or [onFrame] reports there was nothing to draw ahead of.
 *
 * Confined to [scope], like the engine that owns it.
 */
internal class BrowserPlaybackLoops(
  private val scope: CoroutineScope,
  private val isPlaying: () -> Boolean,
  private val onTick: suspend () -> Unit,
  private val onFrame: suspend () -> Boolean,
) {
  private var transport: Job? = null
  private var pump: Job? = null
  private var pumpHandle: Int? = null

  /**
   * Starts both loops.
   */
  fun start() {
    transport =
      scope.launch {
        while (isActive && isPlaying()) {
          delay(TRANSPORT_TICK)
          onTick()
        }
      }
    pumpFrames()
  }

  /**
   * Stops both loops, along with any decode the pump has in flight.
   */
  fun stop() {
    transport?.cancel()
    transport = null
    // The frame callback and the decode it launches are two separate handles, and a preview about
    // to be released has to lose both. Cancelling only the callback leaves a decode running against
    // a preview nobody is going to draw from again.
    pumpHandle?.let { cancelAnimationFrame(it) }
    pumpHandle = null
    pump?.cancel()
    pump = null
  }

  private fun pumpFrames() {
    pumpHandle =
      requestAnimationFrame {
        pumpHandle = null
        pump =
          scope.launch {
            if (isPlaying() && onFrame()) pumpFrames()
          }
      }
  }

  private companion object {
    // Roughly display rate. The position ticker in BasePlayerEngine runs at the same cadence, so
    // nothing here is noticed later than the playhead a listener is already reading.
    val TRANSPORT_TICK = 16.milliseconds
  }
}
