package dev.jordond.filmstrip.player

import platform.AVFAudio.AVAudioSession
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerRateDidChangeReasonKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.numberWithUnsignedLong

/**
 * Posts one audio session notification carrying [code] under [key], the way iOS posts it.
 *
 * The session singleton is the object because that is what the system sends it from. The engine
 * observes with no object filter, so any poster reaches it, and passing the singleton keeps the
 * posting the same shape as the real one rather than proving anything extra.
 */
internal fun postAudioSession(
  name: String?,
  key: String?,
  code: ULong,
) {
  NSNotificationCenter.defaultCenter.postNotificationName(
    aName = name,
    `object` = AVAudioSession.sharedInstance(),
    userInfo = mapOf<Any?, Any?>(key to NSNumber.numberWithUnsignedLong(code)),
  )
}

/**
 * Posts one rate change carrying [reason], the way [player] posts it when its rate moves.
 *
 * The player is the object rather than null. The engine registered for that one player, and an
 * observer scoped to an object reads nothing posted without it.
 */
internal fun postRateChange(
  player: AVPlayer,
  reason: String?,
) {
  NSNotificationCenter.defaultCenter.postNotificationName(
    aName = AVPlayerRateDidChangeNotification,
    `object` = player,
    userInfo = mapOf<Any?, Any?>(AVPlayerRateDidChangeReasonKey to reason),
  )
}
