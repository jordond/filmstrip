package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.PlanFixtures.BITRATE
import dev.jordond.filmstrip.player.internal.PlanFixtures.BROKEN
import dev.jordond.filmstrip.player.internal.PlanFixtures.CAPPED
import dev.jordond.filmstrip.player.internal.PlanFixtures.EDIT
import dev.jordond.filmstrip.player.internal.PlanFixtures.KEEP
import dev.jordond.filmstrip.player.internal.PlanFixtures.KEEP_PLAN
import dev.jordond.filmstrip.player.internal.PlanFixtures.OTHER_EDIT
import dev.jordond.filmstrip.player.internal.PlanFixtures.PARAMETERS_EDIT
import dev.jordond.filmstrip.player.internal.PlanFixtures.PLAN
import dev.jordond.filmstrip.player.internal.PlanFixtures.PLAN_LENGTH
import dev.jordond.filmstrip.player.internal.PlanFixtures.RECAPPED
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannedPlayerEngineTest {
  private val scope = TestScope()
  private val planner = RecordingPlanner()
  private val engine = PlanRecordingEngine(scope, planner)

  @Test
  fun `a new policy plans the loaded edit again and rebuilds`() {
    load(EDIT)

    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    engine.policyOf() shouldBe CAPPED
    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full, EDIT to CAPPED)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the policy already in place plans nothing`() {
    load(EDIT)

    engine.setQualityPolicy(PreviewQualityPolicy.Full)
    scope.runCurrent()

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full)
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a policy set with nothing loaded is kept for the first plan`() {
    engine.setQualityPolicy(CAPPED)
    load(EDIT)

    planner.asked shouldBe listOf(EDIT to CAPPED)
  }

  @Test
  fun `a refused policy reaches the listeners and keeps the attached graph`() {
    load(EDIT)
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)

    planner.answer = { PlanResult.Refused(refusal) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    engine.failures shouldBe listOf(refusal)
    engine.changes shouldBe listOf(CompositionDiff.Structural)
    engine.planOf() shouldBe PLAN
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a good policy after a refused one rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(KEEP_PLAN) }
    engine.setQualityPolicy(RECAPPED)
    scope.runCurrent()

    planner.asked.last() shouldBe (EDIT to RECAPPED)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
    engine.planOf() shouldBe KEEP_PLAN
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `going back to the policy before a refused one rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(PLAN) }
    engine.setQualityPolicy(PreviewQualityPolicy.Full)
    scope.runCurrent()

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `an equal edit after a refused policy plans it again`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(PLAN) }
    load(EDIT)

    planner.asked.last() shouldBe (EDIT to CAPPED)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `a parameters edit under a new policy still rebuilds`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(PLAN) }
    load(EditComposition(tracks = EDIT.tracks, fill = Fill.Blurred()))

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the loaded edit after its item failed rebuilds`() {
    load(EDIT)
    engine.failItem(PlaybackError.UnsupportedFormat(BROKEN))

    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    planner.asked.size shouldBe 2
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the loaded edit after a refused parameters plan rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    load(PARAMETERS_EDIT)

    planner.answer = { PlanResult.Ready(PLAN) }
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    planner.asked.size shouldBe 3
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.asked.size shouldBe 3
  }

  @Test
  fun `the loaded edit after a refused structural plan rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    load(OTHER_EDIT)

    planner.answer = { PlanResult.Ready(PLAN) }
    load(EDIT)

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the loaded edit after a parameters plan that throws rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { error(BROKEN) }
    load(PARAMETERS_EDIT)

    planner.answer = { PlanResult.Ready(PLAN) }
    load(EDIT)

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the loaded edit after apply refuses a parameters edit rebuilds out of the refusal`() {
    load(EDIT)
    engine.refusal = PlaybackError.UnsupportedFormat(BROKEN)
    load(PARAMETERS_EDIT)

    engine.refusal = null
    load(EDIT)

    engine.changes shouldBe
      listOf(CompositionDiff.Structural, CompositionDiff.ParametersOnly, CompositionDiff.Structural)
  }

  @Test
  fun `an edit refused at its first load plays once a policy lets it`() {
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    load(EDIT)

    planner.answer = { PlanResult.Ready(PLAN) }
    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    engine.changes shouldBe listOf(CompositionDiff.Structural)
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a policy set during a load is planned once that load is answered`() {
    val gate = Job()
    planner.gate = gate
    val results = mutableListOf<SetCompositionResult>()
    engine.setComposition(SetCompositionRequest(EDIT)) { results += it }
    scope.runCurrent()

    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()
    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full)

    gate.complete()
    scope.runCurrent()

    results shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full, EDIT to CAPPED)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `a planned edit is answered with the duration of its plan`() {
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    engine.planOf()?.resolved?.duration shouldBe PLAN_LENGTH
  }

  @Test
  fun `an equal edit over a standing graph is answered without planning`() {
    load(EDIT)

    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full)
    engine.changes shouldBe listOf(CompositionDiff.Structural)
  }

  @Test
  fun `an equal edit with no graph standing is planned again`() {
    load(EDIT)
    engine.graphStanding = false

    load(EDIT)

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full, EDIT to PreviewQualityPolicy.Full)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Equal)
  }

  @Test
  fun `a plan apply refuses reaches the listeners and the caller once`() {
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)
    engine.refusal = refusal

    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(refusal))

    engine.failures shouldBe listOf(refusal)
  }

  @Test
  fun `a refused plan reaches the listeners and the caller once`() {
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)
    planner.answer = { PlanResult.Refused(refusal) }

    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(refusal))

    engine.failures shouldBe listOf(refusal)
    engine.changes.shouldBeEmpty()
  }

  @Test
  fun `a plan that throws is refused rather than left waiting`() {
    planner.answer = { error(BROKEN) }

    val expected = PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, BROKEN)
    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(expected))

    engine.failures shouldBe listOf(expected)
  }

  @Test
  fun `a cancelled plan is not reported as a failure`() {
    planner.answer = { throw CancellationException(BROKEN) }

    load(EDIT).shouldBeEmpty()

    engine.failures.shouldBeEmpty()
  }

  @Test
  fun `a spec that moves the grade rebuilds the edit asked for`() {
    load(EDIT)

    planner.answer = { PlanResult.Ready(KEEP_PLAN) }
    engine.setExportSpec(KEEP)
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec(), KEEP)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
    engine.planOf() shouldBe KEEP_PLAN
  }

  @Test
  fun `a spec that leaves the grade alone rebuilds nothing`() {
    load(EDIT)

    engine.setExportSpec(BITRATE)
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec(), BITRATE)
    engine.changes shouldBe listOf(CompositionDiff.Structural)
    engine.failures.shouldBeEmpty()
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.specs shouldBe listOf(ExportSpec(), BITRATE)
  }

  @Test
  fun `the spec already in place plans nothing`() {
    load(EDIT)

    engine.setExportSpec(ExportSpec())
    scope.runCurrent()

    planner.specs shouldBe listOf(ExportSpec())
  }

  @Test
  fun `a spec set with nothing loaded is kept for the first plan`() {
    engine.setExportSpec(KEEP)
    scope.runCurrent()
    planner.specs.shouldBeEmpty()

    load(EDIT)

    planner.specs shouldBe listOf(KEEP)
  }

  @Test
  fun `a refused spec reaches the listeners and keeps the attached graph`() {
    load(EDIT)
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)

    planner.answer = { PlanResult.Refused(refusal) }
    engine.setExportSpec(KEEP)
    scope.runCurrent()

    engine.failures shouldBe listOf(refusal)
    engine.changes shouldBe listOf(CompositionDiff.Structural)
    engine.planOf() shouldBe PLAN
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a good spec after a refused one rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setExportSpec(KEEP)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(PLAN) }
    engine.setExportSpec(BITRATE)
    scope.runCurrent()

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `going back to the spec before a refused one rebuilds out of the refusal`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setExportSpec(KEEP)
    scope.runCurrent()

    planner.answer = { PlanResult.Ready(PLAN) }
    engine.setExportSpec(ExportSpec())
    scope.runCurrent()

    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `an equal edit after a refused spec plans it again`() {
    load(EDIT)
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    engine.setExportSpec(KEEP)
    scope.runCurrent()
    val refusals = engine.failures.size

    load(EDIT)

    engine.failures.size shouldBe refusals + 1
  }

  @Test
  fun `an edit refused at its first load plays once a spec lets it`() {
    planner.answer = { PlanResult.Refused(PlaybackError.UnsupportedFormat(BROKEN)) }
    load(EDIT)

    planner.answer = { PlanResult.Ready(PLAN) }
    engine.setExportSpec(BITRATE)
    scope.runCurrent()

    engine.changes shouldBe listOf(CompositionDiff.Structural)
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a spec set during a load is planned once that load is answered`() {
    val gate = Job()
    planner.gate = gate
    val results = mutableListOf<SetCompositionResult>()
    engine.setComposition(SetCompositionRequest(EDIT)) { results += it }
    scope.runCurrent()

    engine.setExportSpec(KEEP)
    scope.runCurrent()
    planner.specs shouldBe listOf(ExportSpec())

    planner.answer = { PlanResult.Ready(KEEP_PLAN) }
    gate.complete()
    scope.runCurrent()

    results shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.specs shouldBe listOf(ExportSpec(), KEEP)
    engine.planOf() shouldBe KEEP_PLAN
  }

  @Test
  fun `an equal edit during a replan still reaches the new grade`() {
    load(EDIT)

    planner.answer = { PlanResult.Ready(KEEP_PLAN) }
    engine.setExportSpec(KEEP)
    val results = load(EDIT)

    results shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))
    planner.specs shouldBe listOf(ExportSpec(), KEEP)
    engine.planOf() shouldBe KEEP_PLAN
  }

  private fun load(composition: EditComposition): List<SetCompositionResult> {
    val results = mutableListOf<SetCompositionResult>()
    engine.setComposition(SetCompositionRequest(composition)) { results += it }
    scope.runCurrent()
    return results
  }
}
