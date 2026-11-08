package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.postAudioSession
import dev.jordond.filmstrip.player.postRateChange
import io.kotest.matchers.shouldBe
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerRateDidChangeReasonAppBackgrounded
import kotlin.test.Test

/**
 * The three occasions iOS raises, staged by posting the audio session and player notifications the system posts.
 *
 * Each posting carries the `userInfo` code the system puts on it, so what runs is the parsing in
 * `AvInterruptions.ios.kt` with no engine or media around it.
 */
class AvInterruptionsIosTest {
  @Test
  fun `an audio session interruption that began interrupts playback`() {
    heardAfter { stageAudioFocusLost() } shouldBe 1
  }

  @Test
  fun `a route change that lost its output interrupts playback`() {
    heardAfter { stageOutputLost() } shouldBe 1
  }

  @Test
  fun `the watched player put down with the app interrupts playback`() {
    heardAfter { player -> postRateChange(player, AVPlayerRateDidChangeReasonAppBackgrounded) } shouldBe 1
  }

  @Test
  fun `another player put down with the app is not heard`() {
    heardAfter { postRateChange(AVPlayer(), AVPlayerRateDidChangeReasonAppBackgrounded) } shouldBe 0
  }

  @Test
  fun `nothing is heard once disposed and disposing again is harmless`() {
    val player = AVPlayer()
    var interrupted = 0
    val interruptions = AvInterruptions(player) { interrupted++ }

    interruptions.dispose()
    interruptions.dispose()
    stageAudioFocusLost()
    stageOutputLost()
    postRateChange(player, AVPlayerRateDidChangeReasonAppBackgrounded)

    interrupted shouldBe 0
  }

  @Test
  fun `a handler that throws never reaches the system`() {
    val interruptions = AvInterruptions(AVPlayer()) { error("the engine's handler failed") }

    stageAudioFocusLost()

    interruptions.dispose()
  }

  // How many interruptions one observer on a fresh player hears while post runs.
  private fun heardAfter(post: (AVPlayer) -> Unit): Int {
    val player = AVPlayer()
    var interrupted = 0
    val interruptions = AvInterruptions(player) { interrupted++ }
    post(player)
    interruptions.dispose()
    return interrupted
  }

  private fun stageAudioFocusLost() {
    postAudioSession(
      name = AVAudioSessionInterruptionNotification,
      key = AVAudioSessionInterruptionTypeKey,
      code = AVAudioSessionInterruptionTypeBegan,
    )
  }

  private fun stageOutputLost() {
    postAudioSession(
      name = AVAudioSessionRouteChangeNotification,
      key = AVAudioSessionRouteChangeReasonKey,
      code = AVAudioSessionRouteChangeReasonOldDeviceUnavailable,
    )
  }
}
