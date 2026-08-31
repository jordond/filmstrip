package dev.jordond.filmstrip.playback.internal

import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerRateDidChangeReasonAppBackgrounded
import platform.AVFoundation.AVPlayerRateDidChangeReasonAudioSessionInterrupted
import platform.AVFoundation.AVPlayerRateDidChangeReasonKey
import platform.Foundation.NSNotification
import platform.Foundation.NSNumber

/**
 * The iOS interruptions.
 *
 * Three notifications, two of them the audio session's and one the player's own. Audio focus
 * arrives as an interruption notification, a headphone unplug as a route change whose reason is
 * that the old device went away, and the player posts a rate change carrying the reason its rate
 * moved. Two of those reasons are occasions rather than a rate somebody asked for.
 *
 * The rate change is what reports the app being put down, and it reports it by its effect. An app
 * carrying the audio background mode and an `audiovisualBackgroundPlaybackPolicy` of
 * `continuesIfPossible` keeps playing once backgrounded, and a player that kept playing posts no
 * rate change, so nothing is reported.
 *
 * The session interruption is watched alongside it because an interruption does not always move the
 * rate. A composition with no audio track, under a session the host activated, is interrupted while
 * its player carries on.
 *
 * All three need a real device to arrive on their own: the simulator raises none of them, and a
 * host test has no session to interrupt.
 *
 * Only the beginning of an interruption is acted on. Resuming afterwards is the host app's call,
 * since it owns the audio session and knows whether it should take it back.
 */
internal actual class AvInterruptions actual constructor(
  player: AVPlayer,
  onInterrupted: () -> Unit,
) {
  private val observers =
    listOf(
      AvNotificationObserver(AVAudioSessionInterruptionNotification, null) { notification ->
        if (notification.interruptionBegan()) onInterrupted()
      },
      AvNotificationObserver(AVAudioSessionRouteChangeNotification, null) { notification ->
        if (notification.lostItsOutput()) onInterrupted()
      },
      AvNotificationObserver(AVPlayerRateDidChangeNotification, player) { notification ->
        if (isExternalRateChange(notification.rateChangeReason())) onInterrupted()
      },
    )

  actual fun dispose() {
    observers.forEach { it.dispose() }
  }
}

/**
 * Whether a rate change [reason] is an occasion outside filmstrip rather than a rate that was asked
 * for.
 *
 * The engine pauses the player itself on every occasion it acts on, and a host pause reaches the
 * same call. Both post a rate change saying `setRate` was called, so a listener that read the
 * notification without its reason would report filmstrip's own pauses as external ones. Every
 * reason that is not named here, including one from a later system than this one knows about,
 * leaves playback alone.
 */
internal fun isExternalRateChange(reason: String?): Boolean =
  reason == AVPlayerRateDidChangeReasonAudioSessionInterrupted ||
    reason == AVPlayerRateDidChangeReasonAppBackgrounded

private fun NSNotification.interruptionBegan(): Boolean =
  reason(AVAudioSessionInterruptionTypeKey) == AVAudioSessionInterruptionTypeBegan

private fun NSNotification.lostItsOutput(): Boolean =
  reason(AVAudioSessionRouteChangeReasonKey) == AVAudioSessionRouteChangeReasonOldDeviceUnavailable

/**
 * The unsigned code the system put under [key], or null when the notification carries none.
 */
private fun NSNotification.reason(key: String?): ULong? =
  (key?.let { userInfo?.get(it) } as? NSNumber)?.unsignedLongValue

/**
 * The reason string the system put under [AVPlayerRateDidChangeReasonKey], or null when the
 * notification carries none.
 */
private fun NSNotification.rateChangeReason(): String? = userInfo?.get(AVPlayerRateDidChangeReasonKey) as? String
