package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.contract.PlayerEngineContractTest
import kotlinx.coroutines.CoroutineScope
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceWillSleepNotification

/**
 * The shared engine contracts, run against real AVFoundation on the macOS host.
 *
 * The iOS half of the same coverage is in `IosEngineContractTest`, which stages three occasions to
 * this one's one.
 */
class AppleEngineContractTest : PlayerEngineContractTest() {
  init {
    pumpMainRunLoopDuringContracts()
  }

  override fun createEngine(scope: CoroutineScope): EngineUnderTest =
    AppleEngineUnderTest(scope) { _, _ -> stageSleep() }

  override val fixture: EditComposition = appleFixtureComposition()

  /**
   * The one occasion a macOS host process can raise.
   *
   * macOS has no `AVAudioSession`, so [Interruption.AudioFocusLost] has no notification to post
   * here at all, and [Interruption.OutputRouteChanged] arrives through Core Audio HAL device-change
   * listeners that need a real device to be unplugged. Both are named rather than faked. The two in
   * `UNSTAGEABLE_INTERRUPTIONS` are out of reach on every platform.
   */
  override val stageableInterruptions: Set<Interruption> = setOf(Interruption.AppBackgrounded)
}

/**
 * Posts the notification the system posts on its way to sleep, on the centre the engine listens on.
 */
private fun stageSleep() {
  NSWorkspace.sharedWorkspace.notificationCenter.postNotificationName(
    aName = NSWorkspaceWillSleepNotification,
    `object` = null,
  )
}
