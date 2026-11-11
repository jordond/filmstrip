package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVideoPlayerTest {
  @Test
  fun `a new player is ready and paused at the start with its duration`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)

      val state = player.state.value
      state.status shouldBe PlaybackStatus.Ready
      state.playWhenReady.shouldBeFalse()
      state.isSeeking.shouldBeFalse()
      state.duration shouldBe LENGTH
      state.shuttleRate shouldBe null
      player.currentPosition() shouldBe Duration.ZERO
      player.close()
    }

  @Test
  fun `the playhead follows the scheduler while playing and stands still without it`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)

      player.play()
      player.currentPosition() shouldBe Duration.ZERO
      advanceTimeBy(MIDWAY)

      player.shouldBeWithinATickOf(MIDWAY)
      player.state.value.playWhenReady
        .shouldBeTrue()
      player.close()
    }

  @Test
  fun `a paused player holds its playhead however long the test waits`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      player.play()
      advanceTimeBy(MIDWAY)

      player.pause()
      advanceTimeBy(LENGTH)

      player.shouldBeWithinATickOf(MIDWAY)
      player.state.value.status shouldBe PlaybackStatus.Ready
      player.close()
    }

  @Test
  fun `a stalled player holds its playhead and carries on when the stall lifts`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      player.play()
      advanceTimeBy(1.seconds)

      player.setStalled(true)
      advanceTimeBy(3.seconds)
      player.state.value.isStalled
        .shouldBeTrue()
      player.shouldBeWithinATickOf(1.seconds)

      player.setStalled(false)
      advanceTimeBy(1.seconds)
      player.shouldBeWithinATickOf(2.seconds)
      player.close()
    }

  @Test
  fun `reaching the duration ends the player once and stops it`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      val events = player.recordEvents()

      player.play()
      advanceTimeBy(LENGTH + 5.seconds)

      val ended = events.filterIsInstance<PlaybackEvent.Ended>()
      ended shouldHaveSize 1
      ended.single().finalPosition shouldBe LENGTH
      ended.single().duration shouldBe LENGTH
      player.state.value.status shouldBe PlaybackStatus.Ended
      player.state.value.playWhenReady
        .shouldBeFalse()
      player.close()
    }

  @Test
  fun `a seek lands on the frame grid and reports it once`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      val events = player.recordEvents()
      val landing = outputSlotTime(outputSlotOf(OFF_GRID, RATE), RATE)

      player.seekTo(OFF_GRID, SeekAccuracy.Exact)
      runCurrent()

      events.filterIsInstance<PlaybackEvent.SeekCompleted>().map { it.position } shouldContainExactly listOf(landing)
      player.currentPosition() shouldBe landing
      player.state.value.isSeeking
        .shouldBeFalse()
      player.close()
    }

  @Test
  fun `a seek while playing moves the clock and playback carries on from there`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      player.play()
      advanceTimeBy(1.seconds)

      player.seekTo(6.seconds, SeekAccuracy.Exact)
      runCurrent()
      advanceTimeBy(1.seconds)

      player.shouldBeWithinATickOf(7.seconds)
      player.close()
    }

  @Test
  fun `a held seek keeps the player seeking until the test lands it`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      player.holdSeeks = true

      player.seekTo(2.seconds, SeekAccuracy.Exact)
      runCurrent()
      player.state.value.isSeeking
        .shouldBeTrue()

      player.landSeek()
      runCurrent()
      player.state.value.isSeeking
        .shouldBeFalse()
      player.currentPosition() shouldBe 2.seconds
      player.close()
    }

  @Test
  fun `a frame step moves one slot of the grid`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      player.seekTo(2.seconds, SeekAccuracy.Exact)
      runCurrent()

      player.stepFrames(1)
      runCurrent()

      player.currentPosition() shouldBe outputSlotTime(outputSlotOf(2.seconds, RATE) + 1, RATE)
      player.close()
    }

  @Test
  fun `every call is recorded in order with what it was given`() =
    runTest {
      val composition = Composition(emptyList())
      val player = FakeVideoPlayer(this, composition, duration = LENGTH)

      player.play()
      player.seekTo(3.seconds, SeekAccuracy.Nearest)
      player.beginScrub()
      player.endScrub()
      player.setVolume(0.25f)
      player.stepFrames(-2)
      player.pause()
      player.close()

      player.calls shouldContainExactly
        listOf(
          PlayerCall.SetComposition(composition, null, false),
          PlayerCall.Play,
          PlayerCall.SeekTo(3.seconds, SeekAccuracy.Nearest),
          PlayerCall.BeginScrub,
          PlayerCall.EndScrub,
          PlayerCall.SetVolume(0.25f),
          PlayerCall.StepFrames(-2),
          PlayerCall.Pause,
          PlayerCall.Close,
        )
    }

  @Test
  fun `a composition set later is recorded and loads at the duration the test answers with`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      val next = Composition(emptyList())
      player.onSetComposition = { SetCompositionResult.Success(4.seconds) }

      val result = player.setComposition(next, startAt = 1.seconds, playWhenReady = false)

      result shouldBe SetCompositionResult.Success(4.seconds)
      player.compositions shouldHaveSize 2
      player.calls.last() shouldBe PlayerCall.SetComposition(next, 1.seconds, false)
      player.state.value.duration shouldBe 4.seconds
      player.currentPosition() shouldBe 1.seconds
      player.close()
    }

  @Test
  fun `a composition the test fails leaves the player in error`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      val error = PlaybackError.SourceUnreadable("gone")
      player.onSetComposition = { SetCompositionResult.Failure(error) }

      val result = player.setComposition(Composition(emptyList()))

      result shouldBe SetCompositionResult.Failure(error)
      player.state.value.status shouldBe PlaybackStatus.Error(error)
      player.close()
    }

  @Test
  fun `the test can fail the player and send it any event`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)
      val events = player.recordEvents()
      val error = PlaybackError.DecoderUnavailable("busy")

      player.emit(PlaybackEvent.FirstFrameRendered)
      player.fail(error)

      events shouldContainExactly listOf(PlaybackEvent.FirstFrameRendered)
      player.state.value.status
        .shouldBeInstanceOf<PlaybackStatus.Error>()
        .error shouldBe error
      player.close()
    }

  @Test
  fun `the features are the ones the test gave`() =
    runTest {
      val all = FakeVideoPlayer(this)
      val without = FakeVideoPlayer(this, features = PlayerFeature.entries.toSet() - PlayerFeature.Shuttle)

      all.features.supports(PlayerFeature.Shuttle).shouldBeTrue()
      without.features.supports(PlayerFeature.Shuttle).shouldBeFalse()
      without.features.supports(PlayerFeature.FrameStepping).shouldBeTrue()
      all.close()
      without.close()
    }

  @Test
  fun `a closed player is released and takes later calls without moving`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)

      player.close()
      player.play()
      advanceTimeBy(1.seconds)

      player.isClosed.shouldBeTrue()
      player.currentPosition() shouldBe Duration.ZERO
      player.calls.takeLast(2) shouldContainExactly listOf(PlayerCall.Close, PlayerCall.Play)
    }

  private fun FakeVideoPlayer.recordEvents(): List<PlaybackEvent> {
    val events = mutableListOf<PlaybackEvent>()
    addEventListener { events += it }
    return events
  }

  // The playhead is published on the player's position tick, so a reading trails the clock by less than one.
  private fun FakeVideoPlayer.shouldBeWithinATickOf(expected: Duration) {
    withClue("the playhead against $expected") {
      currentPosition() shouldBeLessThanOrEqualTo expected
      currentPosition() shouldBeGreaterThan expected - TICK
    }
  }

  private companion object {
    val LENGTH = 10.seconds
    val MIDWAY = 2_500.milliseconds
    val TICK = 20.milliseconds
    const val RATE = 30

    // Between two slots of a 30 fps grid.
    val OFF_GRID = 1_010.milliseconds
  }
}
