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
import dev.jordond.filmstrip.ffmpeg.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.ffmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An edit lowered for the preview, together with what the preview reports about it.
 *
 * @property resolved The graph the preview builds, which is the graph an export of the same edit
 *   would run.
 * @property info What the preview delivers: the output frame, the preview-only scale and the parity
 *   the plan came back with.
 * @property spec What the pump lowers against, which carries whatever cap the policy asked for.
 * @property layoutSize The frame text lays out against, so a caption wraps on the same words under
 *   a cap as it does in the export.
 */
internal class FfmpegPreviewPlan(
  val resolved: ResolvedComposition,
  val info: PreviewInfo,
  val spec: ExportSpec,
  val layoutSize: Size?,
)

/**
 * The outcome of lowering one edit for the preview.
 */
internal sealed interface FfmpegPlanResult {
  /**
   * The edit lowered.
   */
  class Ready(
    val plan: FfmpegPreviewPlan,
  ) : FfmpegPlanResult

  /**
   * The edit cannot be previewed here.
   */
  class Refused(
    val error: PlaybackError,
  ) : FfmpegPlanResult
}

/**
 * Lowers an edit through the same ffmpeg engine an export of it runs on, and opens the pump on it.
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
internal class FfmpegPreviewPlanner(
  components: ComponentRegistry,
) {
  private val engine = components.ffmpegEngine()

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
  suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): FfmpegPlanResult {
    val natural =
      when (val result = engine.resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> return FfmpegPlanResult.Refused(result.error.toPlaybackError())
        is ResolveResult.Resolved -> result
      }

    val naturalSize = natural.composition.output.size
    val naturalHeight = naturalSize.height
    val cap = (policy as? PreviewQualityPolicy.CapHeight)?.heightPx?.takeIf { it in 1..<naturalHeight }
    val spec = if (cap == null) ExportSpec() else ExportSpec(targetHeight = cap)
    val layoutSize = if (cap == null) null else naturalSize
    val capped =
      when (cap) {
        null -> {
          natural
        }
        else -> {
          when (val result = engine.resolve(composition, spec, layoutSize)) {
            is ResolveResult.Refused -> return FfmpegPlanResult.Refused(result.error.toPlaybackError())
            is ResolveResult.Resolved -> result
          }
        }
      }

    return FfmpegPlanResult.Ready(
      FfmpegPreviewPlan(
        resolved = capped.composition,
        info =
          PreviewInfo(
            outputSize = naturalSize,
            renderScale =
              capped.composition.output.size.height
                .toFloat() / naturalHeight,
            parity = capped.verdict.parity(),
            parityNotes = capped.verdict.parityNotes(),
            fidelity = FFMPEG_FIDELITY,
          ),
        spec = spec,
        layoutSize = layoutSize,
      ),
    )
  }

  /**
   * Spawns a pump for [composition] against the frame [plan] settled on, starting at [at].
   *
   * The spec and the layout frame come off the plan rather than being worked out again, so the
   * process runs the graph the plan reported and not a second lowering of the same edit.
   */
  suspend fun open(
    plan: FfmpegPreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult = engine.openPreview(composition, plan.spec, plan.layoutSize, at)
}

/**
 * The registered ffmpeg engine, or one built for this preview alone.
 *
 * Taken from the registered factory where there is one, so a host that configured the backend gets
 * a preview running against the binaries it named. Falling back builds an engine against a default
 * config, which the host never asked for and which resolves a toolchain of its own.
 */
@OptIn(InternalFilmstripApi::class)
internal fun ComponentRegistry.ffmpegEngine(): FfmpegExportEngine =
  exportEngineFactories.firstNotNullOfOrNull { it.create(this) as? FfmpegExportEngine }
    ?: ffmpegExportEngine(this)

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
 * The four arms a preview can actually reach are named. Everything else is a composition the
 * planner refused, which reads the same to a caller whichever refusal it was.
 */
internal fun ExportError.toPlaybackError(): PlaybackError =
  when (this) {
    is ExportError.ToolchainMissing -> PlaybackError.DecoderUnavailable(message)
    is ExportError.SourceUnreadable -> PlaybackError.SourceUnreadable(message)
    is ExportError.SourceNotExportable -> PlaybackError.SourceNotExportable(message)
    is ExportError.DecoderRejectedInput -> PlaybackError.UnsupportedFormat(message)
    else -> PlaybackError.UnsupportedFormat(message)
  }

/**
 * How much the ffmpeg preview says about each property of the exported file.
 *
 * The preview and the export run one `-filter_complex` over the same decoded frames, so everything
 * the chain decides is exact. What the preview cannot answer for is what happens after it: the
 * encoder it never runs, and a grade it converts straight to RGB rather than handing to a display.
 */
private val FFMPEG_FIDELITY: List<FidelityNote> =
  listOf(
    FidelityNote(
      property = OutputProperty.EncoderArtifacts,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "The preview never runs an encoder, so banding and blocking only appear in an export.",
    ),
    FidelityNote(
      property = OutputProperty.HdrAppearance,
      fidelity = PreviewFidelity.NotPreviewable,
      message = "The pump converts a grade straight to RGB, so an HDR export looks nothing like this.",
    ),
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "Frames are paced as the decoder delivers them, and one that arrives late is late here only.",
    ),
  )

internal val FfmpegPreviewPlan.frameStep: Duration get() = resolved.frameStep

/**
 * How long one frame of this graph's output occupies.
 *
 * Read off the frame rate the negotiation settled, which is the same number every other backend
 * steps and snaps against.
 */
internal val ResolvedComposition.frameStep: Duration
  get() = 1.seconds / checkNotNull(output.frameRate) { "The lowered composition has no frame rate." }
