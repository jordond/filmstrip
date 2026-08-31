package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.internal.Media3PlanResult
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PreviewQualityPolicy

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
  ): Media3PlanResult = throw IllegalStateException(REASON)

  private companion object {
    const val REASON = "the device ran out of decoders while the plan was being resolved"
  }
}
