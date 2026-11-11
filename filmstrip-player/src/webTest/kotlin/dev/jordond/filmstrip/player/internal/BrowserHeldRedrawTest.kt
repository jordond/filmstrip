package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.webFixtureComposition
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.webCodecsBackend
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the browser preview draws at one position as its clock moves, which is what a paused surface pulls each time
 * the engine reports the held frame as redrawn.
 */
class BrowserHeldRedrawTest {
  @Test
  fun `a wall tape drawn twice at one position moves with the preview clock`() =
    contractTest {
      val (first, second) = drawnTwice(Vhs(clock = TapeClock.Wall))

      first.contentEquals(second) shouldBe false
    }

  @Test
  fun `a composition tape drawn twice at one position is the same frame whatever the clock reads`() =
    contractTest {
      val (first, second) = drawnTwice(Vhs())

      first.contentEquals(second) shouldBe true
    }

  private suspend fun drawnTwice(vhs: Vhs): Pair<ByteArray, ByteArray> {
    var now = 1370.milliseconds
    val components =
      Filmstrip {
        webCodecsBackend()
        filters()
      }.components
    val planner = BrowserPreviewPlanner(components, previewClock = PreviewClock { now })
    val plan =
      when (val result = planner.plan(webFixtureComposition(listOf(vhs)), PreviewQualityPolicy.Full, ExportSpec())) {
        is PlanResult.Ready -> result.plan
        is PlanResult.Refused -> fail("the preview refused the fixture: ${result.error.message}")
      }
    val preview = plan.resolved.toBrowserPreview(plan.edit)
    try {
      val position = PROBE_POSITIONS.last()
      val first = preview.frameAt(position)?.pixels?.copyOf() ?: fail("no frame at $position")
      now += CLOCK_STEP
      val second = preview.frameAt(position)?.pixels?.copyOf() ?: fail("no frame at $position")
      return first to second
    } finally {
      preview.release()
    }
  }

  private companion object {
    // Many ticks of the tape's clock, so the two draws cannot share one.
    val CLOCK_STEP: Duration = 500.milliseconds
  }
}
