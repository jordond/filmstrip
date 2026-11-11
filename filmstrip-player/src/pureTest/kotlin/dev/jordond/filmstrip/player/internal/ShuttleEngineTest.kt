package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.ShuttleEdge
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ShuttleEngineTest {
  private val scope = TestScope()
  private val span = 0.seconds..60.seconds
  private val engine = shuttleEngine()
  private val listener = RecordingListener().also { engine.addListener(it) }

  private val edges: List<PlaybackEvent.ShuttleReachedEdge>
    get() = listener.events.filterIsInstance<PlaybackEvent.ShuttleReachedEdge>()

  private fun shuttleEngine(claimed: Set<PlayerFeature> = setOf(PlayerFeature.Shuttle)): FakePlayerEngine =
    FakePlayerEngine(scope, positionTick = TICK, timeSource = scope.testScheduler.timeSource, claimed = claimed)
      .also { it.shuttleSpan = span }

  private fun ready(at: Duration = 30.seconds) {
    engine.becomeReady(60.seconds)
    engine.position = at
  }

  private fun advance(by: Duration) {
    scope.testScheduler.advanceTimeBy(by)
    scope.testScheduler.runCurrent()
  }

  private fun expected(
    origin: Duration,
    rate: Float,
    elapsed: Duration,
  ): Duration = shuttlePlayheadAt(origin, rate, elapsed, span).position

  @Test
  fun `one frame is asked for at a time and the next reads the playhead of that moment`() {
    for (rate in listOf(3f, -3f, 7f, -7f)) {
      val fresh = shuttleEngine()
      fresh.becomeReady(60.seconds)
      fresh.position = 30.seconds
      val started = scope.testScheduler.currentTime.milliseconds

      fresh.beginShuttle(rate)
      fresh.platform.issued.shouldBeEmpty()

      advance(TICK)
      fresh.platform.issued.map { it.position } shouldBe listOf(expected(30.seconds, rate, TICK))

      // The platform is slow. Nothing else goes out while its frame is on the way.
      advance(184.milliseconds)
      fresh.platform.issued shouldHaveSize 1

      fresh.platform.issued[0].complete()
      val elapsed = scope.testScheduler.currentTime.milliseconds - started
      fresh.platform.issued shouldHaveSize 2
      fresh.platform.issued[1].position shouldBe expected(30.seconds, rate, elapsed)
      fresh.platform.issued[1].accuracy shouldBe SeekAccuracy.Exact
    }
  }

  @Test
  fun `the playhead and the state follow the frames shown`() {
    ready()

    engine.beginShuttle(3f)
    listener.states.last().shuttleRate shouldBe 3f
    engine.reported shouldBe 30.seconds

    advance(TICK)
    val first = engine.platform.issued[0]
    engine.reported shouldBe 30.seconds

    first.complete()
    listener.positions.last() shouldBe first.position
    engine.reported shouldBe first.position

    advance(100.milliseconds)
    val second = engine.platform.issued[1]
    second.complete(landed = second.position - 4.milliseconds)
    listener.positions.last() shouldBe second.position - 4.milliseconds
    engine.reported shouldBe second.position - 4.milliseconds
    listener.states.last().isSeeking shouldBe false
    listener.seekCompletions.shouldBeEmpty()
  }

  @Test
  fun `a playhead still on the frame showing asks for nothing`() {
    ready()
    engine.slot = 100.milliseconds

    engine.beginShuttle(0.5f)
    advance(200.milliseconds)
    engine.platform.issued.shouldBeEmpty()

    // The playhead crosses onto the next slot 200 ms in, and the read after that is the thirteenth, at 208 ms.
    advance(TICK)
    engine.platform.issued.map { it.position } shouldBe listOf(expected(30.seconds, 0.5f, 208.milliseconds))
  }

  @Test
  fun `an edge holds its frame and is reported once`() {
    ready(at = 58.seconds)

    engine.beginShuttle(7f)
    advance(TICK)
    engine.platform.issued[0].complete()
    edges.shouldBeEmpty()

    advance(1.seconds)
    engine.platform.issued[1].complete()
    edges.shouldBeEmpty()
    engine.platform.issued[2].position shouldBe 60.seconds
    engine.platform.issued[2].complete()
    edges.map { it.edge to it.position } shouldBe listOf(ShuttleEdge.End to 60.seconds)

    advance(5.seconds)
    engine.platform.issued shouldHaveSize 3
    edges shouldHaveSize 1
    listener.states.last().shuttleRate shouldBe 7f
    listener.positions.last() shouldBe 60.seconds
  }

  @Test
  fun `a shuttle towards the edge it sits on reports it straight away`() {
    ready(at = 0.seconds)

    engine.beginShuttle(-3f)

    edges.map { it.edge to it.position } shouldBe listOf(ShuttleEdge.Start to 0.seconds)
    advance(1.seconds)
    engine.platform.issued.shouldBeEmpty()

    // Asking again is a new arrival.
    engine.beginShuttle(-7f)
    edges shouldHaveSize 2

    // Turning round leaves the edge.
    engine.beginShuttle(3f)
    advance(TICK)
    engine.platform.issued.map { it.position } shouldBe listOf(expected(0.seconds, 3f, TICK))
    edges shouldHaveSize 2
  }

  @Test
  fun `a new rate carries on from the frame showing`() {
    ready()
    engine.beginShuttle(3f)
    advance(TICK)
    val shown = engine.platform.issued[0].position
    engine.platform.issued[0].complete()
    advance(500.milliseconds)

    // The second frame is still on its way, so the frame showing is the first.
    engine.beginShuttle(-7f)
    listener.states.last().shuttleRate shouldBe -7f
    engine.platform.issued[1].complete()
    engine.platform.issued[2].position shouldBe shown

    advance(100.milliseconds)
    engine.platform.issued[2].complete()
    engine.platform.issued[3].position shouldBe expected(shown, -7f, 100.milliseconds)
    engine.shuttling shouldBe listOf(true)
  }

  @Test
  fun `a seek moves the shuttle and completes on the frame it shows`() {
    ready()
    engine.beginShuttle(3f)

    engine.seekTo(10.seconds, SeekAccuracy.Exact)
    listener.seekCompletions.shouldBeEmpty()
    listener.states.last().isSeeking shouldBe false
    engine.reported shouldBe 10.seconds
    engine.platform.issued.map { it.position } shouldBe listOf(10.seconds)

    engine.platform.issued[0].complete()
    listener.seekCompletions.map { it.position } shouldBe listOf(10.seconds)

    advance(TICK)
    engine.platform.issued[1].position shouldBe expected(10.seconds, 3f, TICK)
  }

  @Test
  fun `a seek taken while a frame is on its way waits for it and completes on its own frame`() {
    ready()
    engine.beginShuttle(3f)
    advance(TICK)

    engine.seekTo(10.seconds, SeekAccuracy.Exact)
    engine.platform.issued shouldHaveSize 1

    engine.platform.issued[0].complete()
    listener.seekCompletions.shouldBeEmpty()
    engine.platform.issued
      .map { it.position }
      .last() shouldBe 10.seconds

    engine.platform.issued[1].complete()
    listener.seekCompletions.map { it.position } shouldBe listOf(10.seconds)
  }

  @Test
  fun `a seek to the frame showing completes straight away`() {
    ready()
    engine.beginShuttle(3f)

    engine.seekTo(30.seconds, SeekAccuracy.Exact)

    listener.seekCompletions.map { it.position } shouldBe listOf(30.seconds)
    engine.platform.issued.shouldBeEmpty()
  }

  @Test
  fun `a seek still unanswered when the shuttle ends completes then`() {
    ready()
    engine.beginShuttle(3f)
    engine.seekTo(10.seconds, SeekAccuracy.Exact)

    engine.endShuttle()

    listener.seekCompletions.map { it.position } shouldBe listOf(10.seconds)
  }

  @Test
  fun `ending goes back to playing when it was playing`() {
    ready()
    engine.play()

    engine.beginShuttle(3f)
    engine.pauses shouldBe 1
    listener.states.last().playWhenReady shouldBe false
    engine.shuttling shouldBe listOf(true)

    engine.endShuttle()
    engine.plays shouldBe 2
    listener.states.last().playWhenReady shouldBe true
    listener.states.last().shuttleRate shouldBe null
    engine.shuttling shouldBe listOf(true, false)
  }

  @Test
  fun `ending stays paused when it was paused`() {
    ready()

    engine.beginShuttle(-3f)
    engine.endShuttle()

    engine.plays shouldBe 0
    engine.pauses shouldBe 0
    listener.states.last().playWhenReady shouldBe false
    listener.states.last().shuttleRate shouldBe null
    engine.shuttling shouldBe listOf(true, false)
  }

  @Test
  fun `play and pause end the shuttle into that state`() {
    ready()
    engine.beginShuttle(3f)
    engine.play()
    listener.states.last().shuttleRate shouldBe null
    listener.states.last().playWhenReady shouldBe true
    engine.plays shouldBe 1

    engine.beginShuttle(3f)
    engine.pause()
    listener.states.last().shuttleRate shouldBe null
    listener.states.last().playWhenReady shouldBe false
    engine.plays shouldBe 1
    engine.shuttling shouldBe listOf(true, false, true, false)
  }

  @Test
  fun `a scrub and a frame step end the shuttle`() {
    ready()
    engine.play()

    engine.beginShuttle(3f)
    engine.beginScrub()
    listener.states.last().shuttleRate shouldBe null
    listener.states.last().playWhenReady shouldBe true
    engine.scrubbing shouldBe listOf(true)
    engine.endScrub()

    engine.beginShuttle(3f)
    engine.stepFrames(1)
    listener.states.last().shuttleRate shouldBe null
    engine.steps shouldBe listOf(1)
    engine.shuttling shouldBe listOf(true, false, true, false)
  }

  @Test
  fun `a shuttle begun while scrubbing ends the scrub first`() {
    ready()
    engine.beginScrub()

    engine.beginShuttle(3f)

    engine.scrubbing shouldBe listOf(true, false)
    listener.states.last().shuttleRate shouldBe 3f
  }

  @Test
  fun `a frame that lands after the shuttle ended still moves the playhead`() {
    ready()
    engine.beginShuttle(3f)
    advance(TICK)
    val late = engine.platform.issued[0]

    engine.endShuttle()
    late.complete(landed = 30040.milliseconds)

    listener.positions.last() shouldBe 30040.milliseconds
    advance(1.seconds)
    engine.platform.issued shouldHaveSize 1
  }

  @Test
  fun `a shuttle waits for a seek already in flight`() {
    ready()
    engine.seekTo(20.seconds, SeekAccuracy.Exact)

    engine.beginShuttle(3f)
    advance(200.milliseconds)
    engine.platform.issued.map { it.position } shouldBe listOf(20.seconds)

    engine.platform.issued[0].complete()
    listener.seekCompletions.map { it.position } shouldBe listOf(20.seconds)
    engine.platform.issued shouldHaveSize 2
    engine.platform.issued[1].position shouldBe expected(20.seconds, 3f, 200.milliseconds)
  }

  @Test
  fun `a status that cannot show a frame ends the shuttle`() {
    ready()
    engine.beginShuttle(3f)

    engine.becomePreparing()

    listener.states.last().shuttleRate shouldBe null
    engine.shuttling shouldBe listOf(true, false)
    advance(1.seconds)
    engine.platform.issued.shouldBeEmpty()
  }

  @Test
  fun `nothing loaded ignores the call`() {
    engine.beginShuttle(3f)

    listener.states.last().shuttleRate shouldBe null
    engine.shuttling.shouldBeEmpty()
  }

  @Test
  fun `an engine that does not claim the feature ignores both calls`() {
    val unclaimed = shuttleEngine(claimed = emptySet())
    val watcher = RecordingListener().also { unclaimed.addListener(it) }
    unclaimed.becomeReady(60.seconds)
    unclaimed.play()

    unclaimed.beginShuttle(3f)
    advance(1.seconds)
    unclaimed.endShuttle()

    watcher.states.map { it.shuttleRate }.toSet() shouldBe setOf(null)
    watcher.states.last().playWhenReady shouldBe true
    watcher.events.shouldBeEmpty()
    unclaimed.shuttling.shouldBeEmpty()
    unclaimed.platform.issued.shouldBeEmpty()
    unclaimed.pauses shouldBe 0
  }

  @Test
  fun `an invalid rate throws whether or not the feature is claimed`() {
    ready()
    val unclaimed = shuttleEngine(claimed = emptySet())

    for (rate in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 32.5f, -40f)) {
      assertFailsWith<IllegalArgumentException> { engine.beginShuttle(rate) }
      assertFailsWith<IllegalArgumentException> { unclaimed.beginShuttle(rate) }
    }
    listener.states.last().shuttleRate shouldBe null
  }

  private companion object {
    val TICK = 16.milliseconds
  }
}
