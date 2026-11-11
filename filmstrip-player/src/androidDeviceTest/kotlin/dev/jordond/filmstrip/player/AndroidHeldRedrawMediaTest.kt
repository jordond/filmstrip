package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a paused preview shows under a [Vhs], read off a real surface.
 *
 * A tape on the wall clock keeps moving over the frame the preview holds, and one on composition time stands still.
 * Pictures are counted off the surface, and an emulator is given the time its compositor takes to show one.
 */
class AndroidHeldRedrawMediaTest {
  @Test
  fun aPausedWallTapeKeepsMovingOverTheHeldFrame() =
    onSurface(Vhs(clock = TapeClock.Wall)) { surface ->
      surface.seekAndAwait(HELD_AT)
      // media3 can still be buffering when the seek's frame lands, and the stall clears on its own.
      awaitOrFail("the seek's buffering to settle") { !surface.player.state.value.isStalled }
      delay(COMPOSITOR_LAG)
      val state = surface.player.state.value
      val events = surface.events.size
      val held = surface.player.currentPosition()

      val first = surface.picture()
      withClue("the tape stood still for $MOVES_WITHIN") { surface.pictureAfter(first, MOVES_WITHIN) shouldNotBe first }
      val shown = surface.picturesDuring { delay(WINDOW) }

      // How many a second depends on the device. That it keeps changing is the contract.
      withClue("the surface changed $shown times in $WINDOW") { shown shouldBeGreaterThanOrEqual MIN_PICTURES }
      surface.player.currentPosition() shouldBe held
      surface.player.state.value shouldBe state
      withClue("a held redraw raised ${surface.events.drop(events)}") { surface.events.size shouldBe events }
    }

  @Test
  fun aPausedCompositionTapeHoldsStill() =
    onSurface(Vhs()) { surface ->
      surface.seekAndAwait(HELD_AT)
      delay(SETTLE)

      val shown = surface.picturesDuring { delay(WINDOW) }

      withClue("a tape on composition time moved under a paused preview") { shown shouldBe 0 }
    }

  private fun onSurface(
    vhs: Vhs,
    body: suspend (ShuttleSurface) -> Unit,
  ) = runTest(timeout = TIMEOUT) {
    withContext(Dispatchers.Default) {
      withShuttleSurface(effects = listOf(vhs), configure = { filters() }, body = body)
    }
  }

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val HELD_AT: Duration = 6.seconds
    val WINDOW: Duration = 2.seconds
    val MOVES_WITHIN: Duration = 2.seconds

    // An emulator's software compositor shows a frame some refreshes after media3 hands it over.
    val COMPOSITOR_LAG: Duration = if (runsOnEmulator) 250.milliseconds else Duration.ZERO

    // Long enough for the seek's frame to be on the surface on a phone and on an emulator, before stillness is read.
    val SETTLE: Duration = 500.milliseconds
    const val MIN_PICTURES = 4
  }
}
