package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureClip
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOSStatusErrorDomain
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey
import kotlin.test.Test

/**
 * Which of an item's notifications reach the engine, and how a fresh item reads.
 *
 * The notifications are posted here rather than raised by AVFoundation, on the item they name, the way AVFoundation
 * posts them.
 */
class AvItemObserversMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `the watched item's end failure and stall each reach the engine's dispatcher`() =
    contractTest { scope ->
      val rig = Rig(scope)
      val item = fixtureItem()
      rig.observers.observeItem(item)

      post(AVPlayerItemDidPlayToEndTimeNotification, item)
      post(AVPlayerItemFailedToPlayToEndTimeNotification, item)
      post(AVPlayerItemPlaybackStalledNotification, item)
      awaitContract("all three to reach the engine") { rig.log.size == 3 }

      rig.log shouldBe listOf("end", "failed", "stalled")
      rig.observers.detachItem()
      rig.observers.dispose()
    }

  @Test
  fun `another item's notifications and a detached item's are not heard`() =
    contractTest { scope ->
      val rig = Rig(scope)
      val item = fixtureItem()
      rig.observers.observeItem(item)

      post(AVPlayerItemDidPlayToEndTimeNotification, fixtureItem())
      rig.observers.detachItem()
      post(AVPlayerItemDidPlayToEndTimeNotification, item)
      post(AVPlayerItemPlaybackStalledNotification, item)
      settle()

      rig.log shouldBe emptyList()
      rig.observers.dispose()
    }

  @Test
  fun `no first frame is reported with no layer attached`() =
    contractTest { scope ->
      val rig = Rig(scope)
      val fixture = loadedFixture()
      rig.observers.observeItem(fixture.item)

      rig.observers.reportFirstFrame(fixture.item)

      rig.log shouldBe emptyList()
      rig.observers.surfaceLayer shouldBe null
      rig.observers.detachItem()
      rig.observers.dispose()
    }

  @Test
  fun `an item that names no error reads as a failure with no platform code`() =
    contractTest {
      val error = fixtureItem().playbackError().shouldBeInstanceOf<PlaybackError.Underlying>()

      error.platformCode shouldBe PlaybackError.Underlying.NO_PLATFORM_CODE
    }

  @Test
  fun `an error with one under it keeps its own code and names the one under it in the message`() {
    val under = NSError.errorWithDomain(NSOSStatusErrorDomain, code = -12784, userInfo = null)
    val outer =
      NSError.errorWithDomain(AVFoundationErrorDomain, code = -11800, userInfo = mapOf(NSUnderlyingErrorKey to under))

    val error = outer.toPlaybackError().shouldBeInstanceOf<PlaybackError.Underlying>()

    error.platformCode shouldBe -11800
    error.message shouldBe "${outer.localizedDescription} ($NSOSStatusErrorDomain -12784)"
  }

  @Test
  fun `an error with nothing under it reads as its own description`() {
    val outer = NSError.errorWithDomain(AVFoundationErrorDomain, code = -11800, userInfo = null)

    val error = outer.toPlaybackError().shouldBeInstanceOf<PlaybackError.Underlying>()

    error.platformCode shouldBe -11800
    error.message shouldBe outer.localizedDescription
  }

  @Test
  fun `a stop short reads the error its notification carried when the item names none`() =
    contractTest {
      val under = NSError.errorWithDomain(NSOSStatusErrorDomain, code = -12784, userInfo = null)
      val reason =
        NSError.errorWithDomain(AVFoundationErrorDomain, code = -11800, userInfo = mapOf(NSUnderlyingErrorKey to under))

      fixtureItem().stoppedShortError(reason) shouldBe reason.toPlaybackError()
    }

  @Test
  fun `a stop short reads the item's own error over the one its notification carried`() =
    contractTest {
      val item = AVPlayerItem(asset = AVURLAsset(NSURL.fileURLWithPath(appleFixtureClip() + ".absent"), null))
      val player = AVPlayer(playerItem = item)
      awaitContract("the item to fail") { player.currentItem?.status == AVPlayerItemStatusFailed }
      val reason = NSError.errorWithDomain(AVFoundationErrorDomain, code = -11819, userInfo = null)

      item.stoppedShortError(reason) shouldBe item.playbackError()
      item.stoppedShortError(reason) shouldNotBe reason.toPlaybackError()
    }

  @Test
  fun `a stop short with no error anywhere reads as a failure with no platform code`() =
    contractTest {
      val error = fixtureItem().stoppedShortError(null).shouldBeInstanceOf<PlaybackError.Underlying>()

      error.platformCode shouldBe PlaybackError.Underlying.NO_PLATFORM_CODE
    }

  @Test
  fun `a stop short hands the engine the error its notification carried`() =
    contractTest { scope ->
      val rig = Rig(scope)
      val item = fixtureItem()
      rig.observers.observeItem(item)
      val reason = NSError.errorWithDomain(AVFoundationErrorDomain, code = -11800, userInfo = null)

      NSNotificationCenter.defaultCenter.postNotificationName(
        AVPlayerItemFailedToPlayToEndTimeNotification ?: error("no notification name"),
        `object` = item,
        userInfo = mapOf(AVPlayerItemFailedToPlayToEndTimeErrorKey to reason),
      )
      awaitContract("the stop to reach the engine") { rig.reasons.isNotEmpty() }

      rig.reasons shouldBe listOf(reason)
      rig.observers.detachItem()
      rig.observers.dispose()
    }

  @Test
  fun `an item no player has buffered is stalled and a paused player waits on nothing`() =
    contractTest {
      fixtureItem().isStalled() shouldBe true
      AVPlayer().isWaitingOnData() shouldBe false
    }

  private class Rig(
    scope: CoroutineScope,
  ) {
    val log = mutableListOf<String>()
    val reasons = mutableListOf<NSError?>()

    val observers =
      AvItemObservers(
        scope = scope,
        player = AVPlayer(),
        onChanged = { },
        onReachedEnd = { log += "end" },
        onFailedToPlayToEnd = { _, reason ->
          log += "failed"
          reasons += reason
        },
        onStalled = { log += "stalled" },
        onFirstFrame = { log += "first frame" },
      )
  }

  private suspend fun fixtureItem(): AVPlayerItem =
    AVPlayerItem(asset = appleExportLowering(appleFixtureComposition()).toAvComposition().composition)

  private fun post(
    name: String?,
    item: AVPlayerItem,
  ) {
    NSNotificationCenter.defaultCenter.postNotificationName(name ?: error("no notification name"), `object` = item)
  }
}
