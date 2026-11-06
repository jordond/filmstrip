package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.internal.Media3PreviewPlan
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult

/**
 * A planner that throws instead of refusing.
 *
 * Planning reaches a prober, a resolver and a device query, none of which promise to answer with a
 * refusal rather than an exception. This is what a device under load produced.
 */
internal class ThrowingPlanner : Media3PreviewPlanner(CONTRACT_COMPONENTS) {
  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<Media3PreviewPlan, PlaybackError> = throw IllegalStateException(REASON)

  private companion object {
    const val REASON = "the device ran out of decoders while the plan was being resolved"
  }
}
