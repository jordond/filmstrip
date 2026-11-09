package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * An edit lowered for the preview, in whatever form a backend builds its graph from.
 *
 * Every backend's plan carries the graph and what the preview reports about it, and adds whatever else its own
 * lowering reads.
 */
internal interface PlannedPreview {
  /**
   * The graph the preview builds, which is the graph an export of the same edit would run.
   */
  val resolved: ResolvedComposition

  /**
   * What the preview delivers: the output frame, the preview-only scale and the parity the plan came back with.
   */
  val info: PreviewInfo
}

/**
 * Lowers an edit for one backend's preview, through the same export engine an export of it runs on.
 *
 * A preview that negotiated on its own would settle its own output frame, codec and effect chain, and the two answers
 * would drift apart silently. Going through the export engine means one probe cache, one device answer and one
 * lowering, so the pixels a preview renders come from the graph the file would be written from.
 */
internal interface PreviewPlanner<out P : PlannedPreview> {
  /**
   * Lowers [composition] through [planPreview], capping the rendered frame to whatever [policy] allows.
   *
   * @param exportSpec The export this preview stands in for, whose grade the preview renders at.
   * @return The plan, or why the edit cannot be previewed here.
   */
  suspend fun plan(
    composition: Composition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<P, PlaybackError>
}

/**
 * What every preview says about encoder artifacts, since no preview runs the encoder an export does.
 */
internal val NO_ENCODER_PREVIEW: FidelityNote =
  FidelityNote(
    property = OutputProperty.EncoderArtifacts,
    fidelity = PreviewFidelity.NotPreviewable,
    message = "The preview never runs an encoder, so banding and blocking only appear in an export.",
  )

/**
 * What a preview that hands its grade to the display says about how an HDR export looks.
 */
internal val DISPLAY_DECIDES_HDR: FidelityNote =
  FidelityNote(
    property = OutputProperty.HdrAppearance,
    fidelity = PreviewFidelity.NotPreviewable,
    message = "How an HDR grade looks is decided by the display, not by the composition.",
  )
