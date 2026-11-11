package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.internal.PlanFixtures.EDIT
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlannedHeldRedrawTest {
  private val scope = TestScope()
  private val engine = PlanRecordingEngine(scope, RecordingPlanner())

  @Test
  fun `an effect that redraws while held is found at every scope and not when it is inert`() {
    edit().redrawsWhileHeld() shouldBe false
    edit(clip = listOf(Moving())).redrawsWhileHeld() shouldBe true
    edit(track = listOf(Moving())).redrawsWhileHeld() shouldBe true
    edit(mix = listOf(Moving())).redrawsWhileHeld() shouldBe true
    edit(clip = listOf(Still), mix = listOf(Still)).redrawsWhileHeld() shouldBe false
    edit(mix = listOf(Moving(inert = true))).redrawsWhileHeld() shouldBe false
  }

  @Test
  fun `a paused preview redraws such an edit once a frame of the plan's rate`() {
    engine.redrawsHeld = true
    load(edit(mix = listOf(Moving())))
    engine.becomeReady()

    scope.testScheduler.advanceTimeBy(1.seconds)
    scope.runCurrent()

    // The plan runs at 30 frames a second and a delay counts whole milliseconds, so 34 of them a frame.
    engine.heldRedraws shouldBeInRange 28..30
  }

  @Test
  fun `a backend that cannot draw a held frame again never tries`() {
    load(edit(mix = listOf(Moving())))
    engine.becomeReady()

    scope.testScheduler.advanceTimeBy(1.seconds)
    scope.runCurrent()

    engine.heldRedraws shouldBe 0
  }

  @Test
  fun `an edit swapped in under a paused preview starts and stops the redraws`() {
    engine.redrawsHeld = true
    load(EDIT)
    engine.becomeReady()
    scope.testScheduler.advanceTimeBy(1.seconds)
    engine.heldRedraws shouldBe 0

    load(Composition(tracks = EDIT.tracks, effects = listOf(Moving())))
    scope.testScheduler.advanceTimeBy(1.seconds)
    scope.runCurrent()
    val drawn = engine.heldRedraws
    drawn shouldBeInRange 28..30

    load(EDIT)
    scope.testScheduler.advanceTimeBy(1.seconds)
    scope.runCurrent()
    engine.heldRedraws shouldBe drawn
  }

  private fun load(composition: Composition) {
    engine.setComposition(SetCompositionRequest(composition)) { }
    scope.runCurrent()
  }

  private fun edit(
    clip: List<EffectSpec> = emptyList(),
    track: List<EffectSpec> = emptyList(),
    mix: List<EffectSpec> = emptyList(),
  ): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of("held.mp4"), TimeRange.of(Duration.ZERO, 2.seconds), effects = clip)),
            effects = track,
          ),
        ),
      effects = mix,
    )

  private data class Moving(
    val inert: Boolean = false,
  ) : EffectSpec {
    override val id: String = "test.moving"
    override val redrawsWhileHeld: Boolean get() = true
    override val isInert: Boolean get() = inert
  }

  private data object Still : EffectSpec {
    override val id: String = "test.still"
  }
}
