package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.avfoundation.avFoundationExportEngine
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy

/**
 * Lowers an edit through the same AVFoundation engine an export of it runs on.
 *
 * The resolvers and probers are the ones the owning `Filmstrip` was built with, so a resolver a host
 * registered for its exports lowers the preview too.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class AvPreviewPlanner(
  components: FilmstripComponents,
) : PreviewPlanner<PreviewPlan> {
  private val engine = avFoundationExportEngine(components)

  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): PlanResult<PreviewPlan, PlaybackError> = planPreview(composition, policy, AV_FIDELITY, engine::resolve)
}

/**
 * How much the Core Image preview says about each property of the exported file.
 *
 * The preview and the export run the same `AVVideoComposition` over the same frames, so everything
 * the chain decides is exact. What the preview cannot answer for is what happens after it: the
 * encoder it never runs, and the display's own tone mapping.
 */
private val AV_FIDELITY: List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
    DISPLAY_DECIDES_HDR,
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "A preview frame Core Image could not render in time is dropped. An exported one is not.",
    ),
  )
