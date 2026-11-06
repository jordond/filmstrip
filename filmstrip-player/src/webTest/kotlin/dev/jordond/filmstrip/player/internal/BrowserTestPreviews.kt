package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import kotlin.test.fail

/**
 * [composition] lowered the way the engine lowers it, and the preview graph built on that lowering.
 *
 * The caller owns the graph and releases it.
 */
internal suspend fun browserPreviewOf(composition: EditComposition): Pair<BrowserPreviewPlan, BrowserPreview> {
  val plan =
    when (
      val result =
        BrowserPreviewPlanner(
          CONTRACT_COMPONENTS,
        ).plan(composition, PreviewQualityPolicy.Full, ExportSpec())
    ) {
      is PlanResult.Ready -> result.plan
      is PlanResult.Refused -> fail("the preview refused the fixture: ${result.error.message}")
    }
  return plan to plan.resolved.toBrowserPreview(plan.edit)
}
