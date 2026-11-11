package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.ShuttleEdge
import dev.jordond.filmstrip.player.internal.shuttleBoundsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The shuttle a [FakeVideoPlayer] runs, which is the shipped one over the fake's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeVideoPlayerShuttleTest {
  @Test
  fun `a shuttle carries its rate and reads paused and moves the playhead at the rate`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)

      player.beginShuttle(2f)
      advanceTimeBy(1_500.milliseconds)

      player.state.value.shuttleRate shouldBe 2f
      player.state.value.playWhenReady
        .shouldBeFalse()
      player.shouldBeNear(3.seconds)
      player.close()
    }

  @Test
  fun `a negative rate runs the playhead backwards`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      player.seekTo(6.seconds, SeekAccuracy.Exact)
      runCurrent()

      player.beginShuttle(-2f)
      advanceTimeBy(1_500.milliseconds)

      player.shouldBeNear(3.seconds)
      player.close()
    }

  @Test
  fun `a second shuttle changes the rate and carries on from the frame showing`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      player.beginShuttle(2f)
      advanceTimeBy(1.seconds)

      player.beginShuttle(4f)
      advanceTimeBy(1.seconds)

      player.state.value.shuttleRate shouldBe 4f
      player.shouldBeNear(6.seconds)
      player.close()
    }

  @Test
  fun `ending a shuttle goes back to whichever of playing or paused it was`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)

      player.beginShuttle(2f)
      player.endShuttle()
      player.state.value.shuttleRate shouldBe null
      player.state.value.playWhenReady
        .shouldBeFalse()

      player.play()
      player.beginShuttle(2f)
      player.state.value.playWhenReady
        .shouldBeFalse()
      player.endShuttle()
      player.state.value.shuttleRate shouldBe null
      player.state.value.playWhenReady
        .shouldBeTrue()
      player.close()
    }

  @Test
  fun `the end is reported once with the frame held there and the shuttle stays on`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      val edges = player.recordEdges()
      val last = shuttleBoundsOf(LENGTH, RATE, null).endInclusive

      player.beginShuttle(8f)
      advanceTimeBy(LENGTH)

      edges.map { it.edge to it.position } shouldContainExactly listOf(ShuttleEdge.End to last)
      player.currentPosition() shouldBe last
      player.state.value.shuttleRate shouldBe 8f
      player.close()
    }

  @Test
  fun `a shuttle begun at the edge it heads for reports it straight away`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH, frameRate = RATE)
      val edges = player.recordEdges()

      player.beginShuttle(-4f)
      runCurrent()

      edges.map { it.edge to it.position } shouldContainExactly listOf(ShuttleEdge.Start to Duration.ZERO)
      player.state.value.shuttleRate shouldBe -4f
      player.close()
    }

  @Test
  fun `a rate the player does not take throws and is still recorded`() =
    runTest {
      val player = FakeVideoPlayer(this, duration = LENGTH)

      for (rate in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 32.5f, -32.5f)) {
        assertFailsWith<IllegalArgumentException> { player.beginShuttle(rate) }
      }

      player.calls.filterIsInstance<PlayerCall.BeginShuttle>().size shouldBe 5
      player.state.value.shuttleRate shouldBe null
      player.close()
    }

  @Test
  fun `a player without the shuttle feature ignores the call`() =
    runTest {
      val player = FakeVideoPlayer(this, features = PlayerFeature.entries.toSet() - PlayerFeature.Shuttle)

      player.beginShuttle(2f)
      advanceTimeBy(1.seconds)

      player.state.value.shuttleRate shouldBe null
      player.currentPosition() shouldBe Duration.ZERO
      player.close()
    }

  private fun FakeVideoPlayer.recordEdges(): List<PlaybackEvent.ShuttleReachedEdge> {
    val edges = mutableListOf<PlaybackEvent.ShuttleReachedEdge>()
    addEventListener { if (it is PlaybackEvent.ShuttleReachedEdge) edges += it }
    return edges
  }

  // A shuttle reports each frame as it is shown, so a reading sits within a few frames of where the rate puts it.
  private fun FakeVideoPlayer.shouldBeNear(expected: Duration) {
    withClue("the playhead against $expected") {
      currentPosition() shouldBeGreaterThan expected - SLACK
      currentPosition() shouldBeLessThan expected + SLACK
    }
  }

  private companion object {
    val LENGTH = 10.seconds
    val SLACK = 200.milliseconds
    const val RATE = 30
  }
}
