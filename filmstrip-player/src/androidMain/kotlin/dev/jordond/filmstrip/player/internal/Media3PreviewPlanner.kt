package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.media3ExportEngine
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * An edit lowered for the preview, together with what the preview reports about it.
 *
 * @property resolved The graph the preview builds, which is the graph an export of the same edit
 * would run.
 * @property info What the preview delivers: the output frame, the preview-only scale and the parity
 * the plan came back with.
 * @property bounds The samples each clip decodes, read off the containers the way an export reads
 * them, which the graph lays its items by.
 */
internal class Media3PreviewPlan(
  override val resolved: ResolvedComposition,
  override val info: PreviewInfo,
  val bounds: Media3ClipReads,
) : PlannedPreview

/**
 * Lowers an edit through the same media3 engine an export of it runs on, and reads the samples each clip decodes off
 * the lowered graph.
 *
 * The resolvers and probers are the ones the owning `Filmstrip` was built with, so a resolver a host
 * registered for its exports lowers the preview too.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal open class Media3PreviewPlanner(
  components: FilmstripComponents,
) : PreviewPlanner<Media3PreviewPlan> {
  private val engine = media3ExportEngine(components)

  override suspend fun plan(
    composition: Composition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<Media3PreviewPlan, PlaybackError> =
    when (
      val result =
        planPreview(
          composition,
          policy,
          exportSpec,
          MEDIA3_FIDELITY,
          engine::exportGrade,
          engine::resolve,
        )
    ) {
      is PlanResult.Refused -> {
        result
      }
      is PlanResult.Ready -> {
        val plan = result.plan
        PlanResult.Ready(Media3PreviewPlan(plan.resolved, plan.info, plan.resolved.readClips()))
      }
    }
}

/**
 * How much the media3 preview says about each property of the exported file.
 *
 * The preview and the export run the same effect chain over the same frames through the same
 * `DefaultVideoFrameProcessor`, so everything the chain decides is exact. What the preview cannot
 * answer for is what happens after it: the encoder it never runs, and the display's own tone
 * mapping.
 */
private val MEDIA3_FIDELITY: List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
    DISPLAY_DECIDES_HDR,
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "A preview frame the graph could not render in time is dropped. An exported one is not.",
    ),
  )
