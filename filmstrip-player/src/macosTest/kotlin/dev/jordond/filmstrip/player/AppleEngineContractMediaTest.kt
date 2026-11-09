package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.FailingItem
import dev.jordond.filmstrip.player.contract.Interruption
import dev.jordond.filmstrip.player.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.player.contract.cutInTwo
import dev.jordond.filmstrip.player.internal.NEAREST_SEEK_TOLERANCE
import kotlinx.coroutines.CoroutineScope
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceWillSleepNotification
import kotlin.time.Duration

/**
 * The shared engine contracts, run against real AVFoundation on the macOS host.
 *
 * The iOS half of the same coverage is in `IosEngineContractMediaTest`, which stages three occasions to
 * this one's one.
 */
class AppleEngineContractMediaTest : PlayerEngineContractTest() {
  init {
    pumpMainRunLoopDuringContracts()
  }

  override fun createEngine(scope: CoroutineScope): EngineUnderTest =
    AppleEngineUnderTest(scope) { _, _ -> stageSleep() }

  override fun createFailingItem(scope: CoroutineScope): FailingItem =
    FailingItem(appleEngineFailingItsFirstItem(scope), fixture.cutInTwo(fixtureFrameRate))

  override val fixture: Composition = appleFixtureComposition()

  override val bytesFixture: Composition = appleFixtureBytesComposition()

  override val claimsLiveParameterRedraw: Boolean = true

  override val fixtureFrameRate: Int = FIXTURE_FRAME_RATE

  override val relaxedSeekTolerance: Duration = NEAREST_SEEK_TOLERANCE

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
