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
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.browserExportEngine

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
  val resolved: ResolvedComposition,
  val info: PreviewInfo,
)

/**
 * The outcome of lowering one edit for the preview.
 */
internal sealed interface BrowserPlanResult {
  /**
   * The edit lowered.
   */
  class Ready(
    val plan: BrowserPreviewPlan,
  ) : BrowserPlanResult

  /**
   * The edit cannot be previewed here.
   */
  class Refused(
    val error: PlaybackError,
  ) : BrowserPlanResult
}

/**
 * Lowers an edit through the same WebCodecs engine an export of it runs on.
 *
 * A preview that negotiated on its own would settle its own output frame, codec and effect chain,
 * and the two answers would drift apart silently. Going through the export engine means one probe
 * cache, one device answer and one lowering, so the pixels a preview renders come from the graph
 * the file would be written from.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
@OptIn(InternalFilmstripApi::class)
internal class BrowserPreviewPlanner(
  components: ComponentRegistry,
) {
  private val engine = browserExportEngine(components, chainedProber(components))

  /**
   * Lowers [composition], capping the rendered frame to whatever [policy] allows.
   *
   * The natural frame is settled first and the cap applied against it, so [PreviewInfo.renderScale]
   * is a fraction of the frame the export writes rather than of the frame the cap already produced.
   *
   * The capped lowering is handed that natural frame as the one text lays out against, so a caption
   * wraps on the same words at either size.
   */
  suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): BrowserPlanResult {
    val natural =
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> return BrowserPlanResult.Refused(result.error.toPlaybackError())
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
            is ResolveResult.Refused -> return BrowserPlanResult.Refused(result.error.toPlaybackError())
            is ResolveResult.Resolved -> result
          }
        }
      }

    return BrowserPlanResult.Ready(
      BrowserPreviewPlan(
        edit = composition,
        resolved = capped.composition,
        info =
          PreviewInfo(
            outputSize = naturalSize,
            renderScale =
              capped.composition.output.size.height
                .toFloat() / naturalHeight,
            parity = capped.verdict.parity(),
            parityNotes = capped.verdict.parityNotes(),
            fidelity = BROWSER_FIDELITY,
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
 */
private fun ExportError.toPlaybackError(): PlaybackError =
  when (this) {
    is ExportError.SourceUnreadable -> PlaybackError.SourceUnreadable(message)
    is ExportError.SourceNotExportable -> PlaybackError.SourceNotExportable(message)
    is ExportError.DecoderRejectedInput -> PlaybackError.UnsupportedFormat(message)
    else -> PlaybackError.UnsupportedFormat(message)
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
    FidelityNote(
      property = OutputProperty.EncoderArtifacts,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "The preview never runs an encoder, so banding and blocking only appear in an export.",
    ),
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
