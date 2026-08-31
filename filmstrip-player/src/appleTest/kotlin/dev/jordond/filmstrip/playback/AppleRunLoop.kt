package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.playback.contract.contractPump
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode

/**
 * Points the contract suite's pump at the main run loop.
 *
 * AVFoundation drives an `AVPlayer` from the main run loop and from nowhere else. That was measured
 * rather than assumed: with the loop unpumped an `AVPlayerItem` sits in its unknown status
 * indefinitely, pumping it on the thread that created the player answers nothing unless that thread
 * is the main one, and pumping the main loop moves the item to `readyToPlay` in under a second. A
 * host app runs that loop all the time, and a Kotlin test parks it, so the suite runs it instead.
 *
 * Idempotent, so every Apple contract class can call it from its own initialiser.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pumpMainRunLoopDuringContracts() {
  contractPump = { CFRunLoopRunInMode(kCFRunLoopDefaultMode, PUMP_SECONDS, true) }
}

// Short enough that the suite's own polling stays responsive, long enough that the main thread is
// not spinning on an empty loop.
private const val PUMP_SECONDS = 0.01
