package dev.jordond.filmstrip.player.internal

import platform.AVFoundation.AVPlayer
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceWillSleepNotification
import platform.Foundation.NSNotification
import platform.darwin.NSObjectProtocol

/**
 * The macOS interruptions.
 *
 * macOS has no `AVAudioSession` at all, so audio focus and route change have no notification to
 * watch. The system going to sleep is the one occasion that arrives as a notification, and it is
 * the nearest macOS equivalent of an app being put away by the system.
 *
 * The player is taken and not observed. A rate change reports an audio session interruption or a
 * backgrounding, and macOS raises neither, so the workspace centre is the only one watched here.
 *
 * A headphone unplug is not watched. It reaches macOS through Core Audio HAL device-change
 * listeners on `kAudioHardwarePropertyDefaultOutputDevice`, which needs a real device to change and
 * so is not implemented here.
 */
internal actual class AvInterruptions actual constructor(
  player: AVPlayer,
  onInterrupted: () -> Unit,
) {
  private val center = NSWorkspace.sharedWorkspace.notificationCenter

  private var token: NSObjectProtocol? =
    center.addObserverForName(
      name = NSWorkspaceWillSleepNotification,
      `object` = null,
      queue = null,
    ) { _: NSNotification? ->
      insideObjcFrame(onInterrupted)
    }

  actual fun dispose() {
    token?.let { center.removeObserver(it) }
    token = null
  }
}
