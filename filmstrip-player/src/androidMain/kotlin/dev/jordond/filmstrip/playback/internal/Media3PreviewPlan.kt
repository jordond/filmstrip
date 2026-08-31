package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.media3.media3ExportEngine
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition

/**
 * An edit lowered for the preview, together with what the preview reports about it.
 *
 * @property resolved The graph the preview builds, which is the graph an export of the same edit
 *   would run.
 * @property info What the preview delivers: the output frame, the preview-only scale and the parity
 *   the plan came back with.
 */
internal class Media3PreviewPlan(
  val resolved: ResolvedComposition,
  val info: PreviewInfo,
)

/**
 * The outcome of lowering one edit for the preview.
 */
internal sealed interface Media3PlanResult {
  /**
   * The edit lowered.
   */
  class Ready(
    val plan: Media3PreviewPlan,
  ) : Media3PlanResult

  /**
   * The edit cannot be previewed here.
   */
  class Refused(
    val error: PlaybackError,
  ) : Media3PlanResult
}

/**
 * Lowers an edit through the same media3 engine an export of it runs on.
 *
 * A preview that negotiated on its own would settle its own output frame, codec and effect chain,
 * and the two answers would drift apart silently. Going through the export engine means one probe
 * cache, one device answer and one lowering, so the pixels a preview renders come from the graph
 * the file would be written from.
 *
 * The resolvers and probers are the ones the owning `Filmstrip` was built with, so a resolver a host
 * registered for its exports lowers the preview too.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
@OptIn(InternalFilmstripApi::class)
internal open class Media3PreviewPlanner(
  components: ComponentRegistry,
) {
  private val engine = media3ExportEngine(chainedProber(components), components.effectResolvers)

  /**
   * Lowers [composition], capping the rendered frame to whatever [policy] allows.
   *
   * The natural frame is settled first and the cap applied against it, so [PreviewInfo.renderScale]
   * is a fraction of the frame the export writes rather than of the frame the cap already produced.
   * An uncapped policy, or a cap no smaller than the natural frame, negotiates once.
   *
   * The capped lowering is handed that natural frame as the one text lays out against, so a caption
   * wraps on the same words at either size.
   */
  open suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): Media3PlanResult {
    val natural =
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> return Media3PlanResult.Refused(result.error.toPlaybackError())
        is ResolveResult.Resolved -> result
      }

    val naturalSize = natural.composition.output.size
    val naturalHeight = naturalSize.height
    val cap = (policy as? PreviewQualityPolicy.CapHeight)?.heightPx?.takeIf { it in 1..<naturalHeight }
    val capped =
      when (cap) {
        null -> {
          natural
        }
        else -> {
          when (val result = engine.resolve(composition, ExportSpec(targetHeight = cap), naturalSize)) {
            is ResolveResult.Refused -> return Media3PlanResult.Refused(result.error.toPlaybackError())
            is ResolveResult.Resolved -> result
          }
        }
      }

    return Media3PlanResult.Ready(
      Media3PreviewPlan(
        resolved = capped.composition,
        info =
          PreviewInfo(
            outputSize = naturalSize,
            renderScale =
              capped.composition.output.size.height
                .toFloat() / naturalHeight,
            parity = capped.verdict.parity(),
            parityNotes = capped.verdict.parityNotes(),
            fidelity = MEDIA3_FIDELITY,
          ),
      ),
    )
  }
}

/**
 * The weakest parity across the plan's effects, or [EffectParity.Exact] when it names none.
 */
private fun Verdict.parity(): EffectParity = plan()?.parity ?: EffectParity.Exact

/**
 * The per-effect detail behind the plan's parity.
 */
private fun Verdict.parityNotes(): List<ParityNote> = plan()?.effectOrder?.mapNotNull { it.note }.orEmpty()

private fun Verdict.plan(): ExportPlan? =
  when (this) {
    is Verdict.Capable -> plan
    is Verdict.Degraded -> plan
    is Verdict.Incapable -> withoutUnsupported
  }

/**
 * What a failed lowering means to a player.
 *
 * The two arms a preview can actually reach are named. Everything else is a composition the planner
 * refused, which reads the same to a caller whichever refusal it was.
 */
private fun ExportError.toPlaybackError(): PlaybackError =
  when (this) {
    is ExportError.SourceUnreadable -> PlaybackError.SourceUnreadable(message)
    is ExportError.SourceNotExportable -> PlaybackError.SourceNotExportable(message)
    is ExportError.DecoderRejectedInput -> PlaybackError.UnsupportedFormat(message)
    else -> PlaybackError.UnsupportedFormat(message)
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
    FidelityNote(
      property = OutputProperty.EncoderArtifacts,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "The preview never runs an encoder, so banding and blocking only appear in an export.",
    ),
    FidelityNote(
      property = OutputProperty.HdrAppearance,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "How an HDR grade looks is decided by the display, not by the composition.",
    ),
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "A preview frame the graph could not render in time is dropped. An exported one is not.",
    ),
  )
