@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TryEngineWatchTest {
  private val source = MediaSource.ofUri("blob:beach")

  private fun TestScope.engine() =
    TryEngine(backgroundScope, exporter = { _, _ -> flowOf() }, loadClip = { source }, save = {}, release = {})

  @Test
  fun `watching starts the player`() =
    runTest {
      val player = fakePlayer(source)
      engine().watch(player)
      runCurrent()
      player.plays shouldBe 1
    }

  @Test
  fun `the loop range covers the edit and follows a trim`() =
    runTest {
      val player = fakePlayer(source)
      engine().watch(player)

      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()
      player.show(PlaybackStatus.Preparing, 12.seconds)
      runCurrent()
      player.show(PlaybackStatus.Ready, 6.seconds)
      runCurrent()

      player.loopRanges shouldContainExactly
        listOf(TimeRange.of(Duration.ZERO, 12.seconds), TimeRange.of(Duration.ZERO, 6.seconds))
    }

  @Test
  fun `no duration sets no loop range and leaves the status without a duration`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      player.show(PlaybackStatus.Preparing)
      runCurrent()
      player.loopRanges shouldBe emptyList()
      statusOf(engine.state.value).duration.shouldBeNull()
    }

  @Test
  fun `the playhead reaches the status about ten times a second`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      runCurrent()
      player.ticks shouldContainExactly listOf(100.milliseconds)

      player.playhead.value = 5250.milliseconds
      runCurrent()

      statusOf(engine.state.value).position shouldBe 5250.milliseconds
    }

  @Test
  fun `the status reads the playhead in source time under a trim`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      engine.apply(Control.SetSteps(Steps(trim = true)))

      player.playhead.value = 3250.milliseconds
      runCurrent()

      statusOf(engine.state.value).position shouldBe 5250.milliseconds
    }

  @Test
  fun `the status reports the whole clip under a trim once a player has it up`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      engine.apply(Control.SetSteps(Steps(trim = true)))

      player.show(PlaybackStatus.Ready, 6.seconds)
      runCurrent()

      statusOf(engine.state.value).duration shouldBe beachClip.duration
    }

  @Test
  fun `a rebuild that loses the duration keeps the picture showing`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()

      player.show(PlaybackStatus.Preparing)
      runCurrent()

      statusOf(engine.state.value).duration shouldBe beachClip.duration
    }

  @Test
  fun `a player error takes the picture down and clears when it recovers`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()

      player.show(PlaybackStatus.Error(PlaybackError.SourceUnreadable("The clip is unreadable.")), 12.seconds)
      runCurrent()
      statusOf(engine.state.value).error shouldBe "The clip is unreadable."
      statusOf(engine.state.value).duration.shouldBeNull()

      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()
      engine.state.value.error
        .shouldBeNull()
      statusOf(engine.state.value).duration shouldBe beachClip.duration
    }

  @Test
  fun `retry with the picture up does nothing`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.watch(player)
      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()
      val attempt = engine.state.value.attempt

      engine.apply(Control.Retry)
      runCurrent()

      engine.state.value.attempt shouldBe attempt
      statusOf(engine.state.value).duration shouldBe beachClip.duration
    }

  @Test
  fun `retry after a player error asks for a new player`() =
    runTest {
      val player = fakePlayer(source)
      val engine = engine()
      engine.load()
      runCurrent()
      engine.watch(player)
      player.show(PlaybackStatus.Error(PlaybackError.SourceUnreadable("The clip is unreadable.")))
      runCurrent()
      val attempt = engine.state.value.attempt

      engine.apply(Control.Retry)
      runCurrent()

      engine.state.value.attempt shouldBe attempt + 1
      engine.state.value.source shouldBe source
      engine.state.value.error
        .shouldBeNull()
    }

  @Test
  fun `recovery leaves an error the player did not raise`() =
    runTest {
      val player = fakePlayer(source)
      val engine =
        TryEngine(
          backgroundScope,
          exporter = { _, _ -> flow { throw IllegalStateException("A frame this large is refused.") } },
          loadClip = { source },
          save = {},
          release = {},
        )
      engine.load()
      runCurrent()
      engine.watch(player)
      engine.apply(Control.Export)
      runCurrent()

      player.show(PlaybackStatus.Ready, 12.seconds)
      runCurrent()

      engine.state.value.error shouldBe "A frame this large is refused."
    }

  @Test
  fun `a new player takes over from the one before`() =
    runTest {
      val first = fakePlayer(source)
      val second = fakePlayer(source)
      val engine = engine()
      engine.watch(first)
      runCurrent()
      engine.watch(second)
      runCurrent()

      first.show(PlaybackStatus.Error(PlaybackError.SourceUnreadable("Gone.")))
      runCurrent()

      engine.state.value.error
        .shouldBeNull()
    }
}
