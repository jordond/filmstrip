@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * How playback comes back after the browser stops it, on its own for a hidden page and on a tap for refused autoplay.
 */
class TryEngineResumeTest {
  private val source = MediaSource.ofUri("blob:beach")

  private fun TestScope.watching(player: FakePlayer): TryEngine {
    val engine =
      TryEngine(backgroundScope, exporter = { _, _ -> flowOf() }, loadClip = { source }, save = {}, release = {})
    engine.load()
    runCurrent()
    engine.watch(player)
    runCurrent()
    return engine
  }

  @Test
  fun `a page hidden and shown again starts the player where it stopped`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.plays shouldBe 1

      engine.onPageHidden(true)
      player.platformPause()
      runCurrent()
      engine.state.value.awaitingTap shouldBe false
      player.plays shouldBe 1

      engine.onPageHidden(false)
      runCurrent()
      player.plays shouldBe 2
      engine.state.value.awaitingTap shouldBe false
    }

  @Test
  fun `showing a page that never stopped the player starts nothing`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)

      engine.onPageHidden(true)
      engine.onPageHidden(false)
      runCurrent()

      player.plays shouldBe 1
    }

  @Test
  fun `playback the browser takes on a showing page waits for a tap`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)

      player.platformPause()
      runCurrent()
      statusOf(engine.state.value).awaitingTap shouldBe true
      player.plays shouldBe 1

      engine.onGesture()
      runCurrent()
      statusOf(engine.state.value).awaitingTap shouldBe false
      player.plays shouldBe 2
    }

  @Test
  fun `a gesture with nothing refused starts nothing`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)

      engine.onGesture()
      runCurrent()

      player.plays shouldBe 1
    }

  @Test
  fun `a tap the browser still refuses asks again`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.platformPause()
      runCurrent()

      engine.onGesture()
      runCurrent()
      player.platformPause()
      runCurrent()

      statusOf(engine.state.value).awaitingTap shouldBe true
    }

  @Test
  fun `showing the page while a tap is awaited leaves it to the tap`() =
    runTest {
      val player = fakePlayer(source)
      val engine = watching(player)
      player.platformPause()
      runCurrent()

      engine.onPageHidden(true)
      engine.onPageHidden(false)
      runCurrent()

      player.plays shouldBe 1
      statusOf(engine.state.value).awaitingTap shouldBe true
    }

  @Test
  fun `a new player does not inherit the tap`() =
    runTest {
      val first = fakePlayer(source)
      val engine = watching(first)
      first.platformPause()
      runCurrent()

      val second = fakePlayer(source)
      engine.watch(second)
      runCurrent()

      statusOf(engine.state.value).awaitingTap shouldBe false
    }
}
