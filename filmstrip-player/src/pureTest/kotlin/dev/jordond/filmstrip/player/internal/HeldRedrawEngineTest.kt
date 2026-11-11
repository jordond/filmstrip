package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlayerFeature
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HeldRedrawEngineTest {
  private val scope = TestScope()
  private val engine =
    FakePlayerEngine(
      scope,
      positionTick = 16.milliseconds,
      timeSource = scope.testScheduler.timeSource,
      claimed = setOf(PlayerFeature.Shuttle),
    ).also { it.shuttleSpan = 0.seconds..60.seconds }
  private val listener = RecordingListener().also { engine.addListener(it) }

  private fun advance(by: Duration) {
    scope.testScheduler.advanceTimeBy(by)
    scope.testScheduler.runCurrent()
  }

  private fun held() {
    engine.heldInterval = FRAME
    engine.becomeReady(60.seconds)
  }

  @Test
  fun `a held frame is drawn again once every interval`() {
    held()

    advance(FRAME * 2.5)
    engine.heldRedraws shouldBe 2
    advance(FRAME * 7.5)
    engine.heldRedraws shouldBe 10
  }

  @Test
  fun `nothing is redrawn without an interval or before a frame is shown`() {
    engine.becomeReady(60.seconds)
    advance(FRAME * 10)
    engine.heldRedraws shouldBe 0

    engine.becomePreparing()
    engine.heldInterval = FRAME
    engine.resync()
    advance(FRAME * 10)
    engine.heldRedraws shouldBe 0
  }

  @Test
  fun `an interval that arrives under a paused frame starts the redraws and one that leaves stops them`() {
    engine.becomeReady(60.seconds)
    engine.heldInterval = FRAME
    engine.resync()
    advance(FRAME * 3)
    engine.heldRedraws shouldBe 3

    engine.heldInterval = null
    engine.resync()
    advance(FRAME * 3)
    engine.heldRedraws shouldBe 3
  }

  @Test
  fun `playing stops the redraws and pausing starts them again`() {
    held()
    advance(FRAME * 2)

    engine.play()
    advance(FRAME * 5)
    engine.heldRedraws shouldBe 2

    engine.pause()
    advance(FRAME * 3)
    engine.heldRedraws shouldBe 5
  }

  @Test
  fun `a seek in flight and a shuttle both hold the redraws off`() {
    held()
    advance(FRAME)
    engine.heldRedraws shouldBe 1

    engine.seekTo(5.seconds)
    advance(FRAME * 4)
    engine.heldRedraws shouldBe 1
    engine.platform.issued
      .last()
      .complete()
    advance(FRAME * 2)
    engine.heldRedraws shouldBe 3

    engine.beginShuttle(3f)
    val before = engine.heldRedraws
    advance(FRAME * 4)
    engine.heldRedraws shouldBe before
  }

  @Test
  fun `a failure and a release both end the redraws`() {
    held()
    advance(FRAME)

    engine.fail(PlaybackError.Underlying(1, "gone"))
    advance(FRAME * 4)
    engine.heldRedraws shouldBe 1

    engine.becomeReady(60.seconds)
    advance(FRAME)
    engine.heldRedraws shouldBe 2
    engine.dispose()
    advance(FRAME * 4)
    engine.heldRedraws shouldBe 2
  }

  @Test
  fun `each redraw is reported as a redrawn frame and as nothing else`() {
    held()
    val events = listener.events.size
    val positions = listener.positions.size
    val states = listener.states.size

    advance(FRAME * 6)

    engine.heldRedraws shouldBe 6
    listener.frameRedraws shouldBe 6
    listener.events.size shouldBe events
    listener.positions.size shouldBe positions
    listener.states.size shouldBe states
  }

  private companion object {
    // A whole number of milliseconds, since a delay is counted in them.
    val FRAME: Duration = 40.milliseconds
  }
}
