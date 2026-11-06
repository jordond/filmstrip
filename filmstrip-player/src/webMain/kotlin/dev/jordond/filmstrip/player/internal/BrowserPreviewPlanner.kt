package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
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
internal open class BrowserPreviewPlanner(
  components: FilmstripComponents,
) : PreviewPlanner<BrowserPreviewPlan> {
  private val engine = webCodecsExportEngine(components)

  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<BrowserPreviewPlan, PlaybackError> =
    // What the preview says about the grade follows the grade the plan settles on, so the notes go on once it has.
    when (
      val result =
        planPreview(
          composition,
          policy,
          exportSpec,
          emptyList(),
          engine::exportGrade,
          engine::resolve,
        )
    ) {
      is PlanResult.Refused -> {
        result
      }
      is PlanResult.Ready -> {
        val resolved = result.plan.resolved
        val info = result.plan.info
        val fidelity = browserFidelity(resolved.hdr)
        PlanResult.Ready(
          BrowserPreviewPlan(
            composition,
            resolved,
            PreviewInfo(info.outputSize, info.renderScale, info.parity, info.parityNotes, fidelity, info.inertNotices),
          ),
        )
      }
    }
}

/**
 * How much the browser preview says about each property of a file exported under [hdr].
 *
 * The preview draws through the compositor the encoder takes its frames from, so everything that
 * pass decides is exact. What it cannot answer for is what happens after it: the encoder it never
 * runs, and a kept grade the canvas has no way to show. A tone mapped export is SDR from the
 * composite on, so the canvas shows what the file holds.
 */
internal fun browserFidelity(hdr: ResolvedHdr): List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
    when (hdr) {
      ResolvedHdr.Keep -> KEPT_GRADE
      ResolvedHdr.ToneMap -> TONE_MAPPED
    },
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "A frame the decoder could not deliver in time is held. An exported one is not.",
    ),
  )

private val KEPT_GRADE =
  FidelityNote(
    property = OutputProperty.HdrAppearance,
    fidelity = PreviewFidelity.NotPreviewable,
    message = "The canvas composites in standard range, so an HDR grade is not what the preview shows.",
  )

private val TONE_MAPPED =
  FidelityNote(
    property = OutputProperty.HdrAppearance,
    fidelity = PreviewFidelity.Exact,
    message = "The preview runs the tone curve the export does, so the SDR frame it shows is the one the file holds.",
  )
