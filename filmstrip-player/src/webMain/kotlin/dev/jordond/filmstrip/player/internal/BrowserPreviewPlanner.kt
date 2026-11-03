package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine

/**
 * An edit lowered for the preview, together with what the preview reports about it.
 *
 * @property edit The edit the plan came from, which the browser lowering reads each clip's own
 *   geometry off.
 * @property resolved The graph the preview builds, which is the graph an export of the same edit
 *   would run.
 * @property info What the preview delivers: the output frame, the preview-only scale and the parity
 *   the plan came back with.
 */
internal class BrowserPreviewPlan(
  val edit: EditComposition,
  override val resolved: ResolvedComposition,
  override val info: PreviewInfo,
) : PlannedPreview

/**
 * Lowers an edit through the same WebCodecs engine an export of it runs on, and carries the edit beside the graph.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class BrowserPreviewPlanner(
  components: FilmstripComponents,
) : PreviewPlanner<BrowserPreviewPlan> {
  private val engine = webCodecsExportEngine(components)

  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): PlanResult<BrowserPreviewPlan, PlaybackError> =
    when (val result = planPreview(composition, policy, BROWSER_FIDELITY, engine::resolve)) {
      is PlanResult.Refused -> result
      is PlanResult.Ready -> PlanResult.Ready(BrowserPreviewPlan(composition, result.plan.resolved, result.plan.info))
    }
}

/**
 * How much the browser preview says about each property of the exported file.
 *
 * The preview draws through the compositor the encoder takes its frames from, so everything that
 * pass decides is exact. What it cannot answer for is what happens after it: the encoder it never
 * runs, and a grade the canvas has no way to show.
 */
private val BROWSER_FIDELITY: List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
    FidelityNote(
      property = OutputProperty.HdrAppearance,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "The canvas composites in standard range, so an HDR grade is not what the preview shows.",
    ),
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "A frame the decoder could not deliver in time is held. An exported one is not.",
    ),
  )
