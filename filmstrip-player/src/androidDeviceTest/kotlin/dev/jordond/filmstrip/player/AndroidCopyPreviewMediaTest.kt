package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long media3's player says a copy runs when the copy's window ends between two slots of the grid.
 *
 * The copy's video stops on the grid and its audio runs to the window's end, so the player's own length is the plan's
 * duration rather than the grid's end. The contract fixture's window ends on a slot, where the two read the same.
 */
class AndroidCopyPreviewMediaTest {
  @Test
  fun anOffGridCopyPlaysForThePlansDuration() =
    contractTest { scope ->
      val edit = offGridCopy()
      val plan =
        Media3PreviewPlanner(CONTRACT_COMPONENTS)
          .plan(edit, PreviewQualityPolicy.Full, ExportSpec())
          .shouldBeInstanceOf<PlanResult.Ready<Media3PreviewPlan>>()
          .plan
          .resolved
      withClue("an untouched window from the source's start is meant to copy") { plan.path shouldBe ExportPath.Copy }
      withClue("the window was meant to end between two slots") {
        outputSlotTime(plan.videoSlots, plan.output.frameRate) shouldNotBe plan.duration
      }

      val subject = AndroidEngineUnderTest(scope)
      withEngine(subject.engine) {
        subject.engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the platform to report a duration of its own") { subject.platformDuration != null }

        subject.platformDuration shouldBe plan.duration
      }
    }

  private fun offGridCopy(): EditComposition =
    EditComposition(listOf(Track(listOf(Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, OFF_GRID_END))))))

  private companion object {
    // A third of a slot past the thirtieth at the fixture's rate, so the grid ends on the whole second before it.
    val OFF_GRID_END = 1_010.milliseconds
  }
}
