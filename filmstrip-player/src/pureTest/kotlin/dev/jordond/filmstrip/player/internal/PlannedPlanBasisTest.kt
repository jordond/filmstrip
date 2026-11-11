package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.PlanFixtures.EDIT
import dev.jordond.filmstrip.player.internal.PlanFixtures.KEEP_PLAN
import dev.jordond.filmstrip.player.internal.PlanFixtures.PLAN_LENGTH
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test

/**
 * A planner that reads something besides the edit, the policy and the spec, like the kind of surface a preview draws
 * into, and what the engine does when that moves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannedPlanBasisTest {
  private val scope = TestScope()
  private val planner = RecordingPlanner()
  private val engine = PlanRecordingEngine(scope, planner)

  @Test
  fun `a plan basis that moves the grade rebuilds the edit asked for`() {
    load(EDIT)

    planner.answer = { PlanResult.Ready(KEEP_PLAN) }
    engine.basis = BASIS
    engine.basisMoved()
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec(), ExportSpec())
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
    engine.planOf() shouldBe KEEP_PLAN
  }

  @Test
  fun `a plan basis that leaves the grade alone plans again and rebuilds nothing`() {
    load(EDIT)

    engine.basis = BASIS
    engine.basisMoved()
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec(), ExportSpec())
    engine.changes shouldBe listOf(CompositionDiff.Structural)
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.specs shouldBe listOf(ExportSpec(), ExportSpec())
  }

  @Test
  fun `a plan basis that has not moved plans nothing`() {
    load(EDIT)

    engine.basisMoved()
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec())
  }

  @Test
  fun `a plan basis moved with nothing loaded is kept for the first plan`() {
    engine.basis = BASIS
    engine.basisMoved()
    scope.runCurrent()
    planner.specs.shouldBeEmpty()

    load(EDIT)
    engine.basisMoved()
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec())
  }

  private fun load(composition: Composition): List<SetCompositionResult> {
    val results = mutableListOf<SetCompositionResult>()
    engine.setComposition(SetCompositionRequest(composition)) { results += it }
    scope.runCurrent()
    return results
  }

  private companion object {
    const val BASIS = "a surface that shows SDR only"
  }
}
