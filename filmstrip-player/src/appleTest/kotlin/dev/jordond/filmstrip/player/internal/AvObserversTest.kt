package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import io.kotest.matchers.shouldBe
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.status
import platform.AVFoundation.videoComposition
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObject
import kotlin.concurrent.AtomicInt
import kotlin.test.Test

class AvObserversTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a notification observer hears its name posted by the object it watches and nothing else`() {
    val watched = NSObject()
    val posted = mutableListOf<NSNotification>()
    val observer = AvNotificationObserver(NAME, watched) { posted += it }

    post(watched)
    post(NSObject())

    posted.size shouldBe 1
    posted.single().`object` shouldBe watched
    observer.dispose()
  }

  @Test
  fun `a disposed notification observer hears nothing and disposes again harmlessly`() {
    val watched = NSObject()
    var heard = 0
    val observer = AvNotificationObserver(NAME, watched) { heard++ }

    observer.dispose()
    observer.dispose()
    post(watched)

    heard shouldBe 0
  }

  @Test
  fun `a notification observer that throws never lets it reach the poster`() {
    val watched = NSObject()
    val observer = AvNotificationObserver(NAME, watched) { error("the engine's handler failed") }

    post(watched)

    observer.dispose()
  }

  @Test
  fun `a key value observer hears an item it watches become ready`() =
    contractTest {
      val changes = AtomicInt(0)
      val observer = AvKeyValueObserver { changes.incrementAndGet() }
      val item = fixtureItem()

      observer.observeItem(item)
      val player = AVPlayer(playerItem = item)
      awaitContract("the item to be ready") { item.status == AVPlayerItemStatusReadyToPlay }
      awaitContract("the observer to hear the item move") { changes.value > 0 }

      observer.observeItem(null)
      observer.dispose()
      player.replaceCurrentItemWithPlayerItem(null)
    }

  @Test
  fun `a key value observer hears the player's transport and nothing once disposed`() =
    contractTest {
      val changes = AtomicInt(0)
      val observer = AvKeyValueObserver { changes.incrementAndGet() }
      val item = fixtureItem()
      val player = AVPlayer(playerItem = item)
      awaitContract("the item to be ready") { item.status == AVPlayerItemStatusReadyToPlay }

      observer.observePlayer(player)
      player.play()
      awaitContract("the observer to hear the transport start") { changes.value > 0 }
      player.pause()
      awaitContract("the observer to hear the transport stop") { changes.value > 1 }

      observer.dispose()
      observer.dispose()
      val heard = changes.value
      player.play()
      settle()
      player.pause()

      changes.value shouldBe heard
    }

  @Test
  fun `a key value observer that throws keeps delivering`() {
    val calls = AtomicInt(0)
    val observer =
      AvKeyValueObserver {
        calls.incrementAndGet()
        error("the engine's handler failed")
      }

    observer.changed()
    observer.changed()

    calls.value shouldBe 2
    observer.dispose()
  }

  private suspend fun fixtureItem(): AVPlayerItem {
    val av = appleExportLowering(appleFixtureComposition()).toAvComposition()
    return AVPlayerItem(asset = av.composition).apply { videoComposition = av.videoComposition }
  }

  private fun post(from: Any) {
    NSNotificationCenter.defaultCenter.postNotificationName(NAME, `object` = from)
  }

  private companion object {
    const val NAME = "dev.jordond.filmstrip.test.AvObserversTest"
  }
}
