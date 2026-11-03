package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.shouldBe
import platform.AVFoundation.AVPlayer
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceWillSleepNotification
import kotlin.test.Test

/**
 * The one occasion macOS raises, staged by posting what the system posts on its way to sleep.
 */
class AvInterruptionsMacosTest {
  @Test
  fun `the system going to sleep interrupts playback`() {
    var interrupted = 0
    val interruptions = AvInterruptions(AVPlayer()) { interrupted++ }

    stageSleep()

    interrupted shouldBe 1
    interruptions.dispose()
  }

  @Test
  fun `nothing is heard once disposed and disposing again is harmless`() {
    var interrupted = 0
    val interruptions = AvInterruptions(AVPlayer()) { interrupted++ }

    interruptions.dispose()
    interruptions.dispose()
    stageSleep()

    interrupted shouldBe 0
  }

  @Test
  fun `a handler that throws never reaches the system`() {
    val interruptions = AvInterruptions(AVPlayer()) { error("the engine's handler failed") }

    stageSleep()

    interruptions.dispose()
  }

  private fun stageSleep() {
    NSWorkspace.sharedWorkspace.notificationCenter.postNotificationName(
      aName = NSWorkspaceWillSleepNotification,
      `object` = null,
    )
  }
}
