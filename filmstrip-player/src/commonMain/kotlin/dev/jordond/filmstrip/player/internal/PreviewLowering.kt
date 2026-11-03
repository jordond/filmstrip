package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * Lowers an edit into the graph an export of it runs, the way each backend's export engine does.
 *
 * Takes the edit, the spec to lower against and the frame text lays out against, where null lays text out against the
 * frame the spec settles on. Every backend hands over its engine's own `resolve`.
 */
internal typealias CompositionResolve = suspend (EditComposition, ExportSpec, Size?) -> ResolveResult

/**
 * The height a frame [naturalHeight] pixels tall renders at when [heightPx] is asked for, or null when it renders at
 * its own height.
 *
 * Only a height from one up to just below [naturalHeight] moves the frame. Null, zero and anything at or above the
 * natural height all leave it alone, so a frame is never scaled up.
 */
internal fun cappedHeight(
  heightPx: Int?,
  naturalHeight: Int,
): Int? = heightPx?.takeIf { it in 1..<naturalHeight }

/**
 * The height this policy renders a frame [naturalHeight] pixels tall at, or null when it renders at its own height.
 *
 * [PreviewQualityPolicy.Full] never moves the frame. [PreviewQualityPolicy.CapHeight] moves it under the rule
 * [cappedHeight] applies.
 */
internal fun PreviewQualityPolicy.cappedHeight(naturalHeight: Int): Int? = cappedHeight(heightCap, naturalHeight)

private val PreviewQualityPolicy.heightCap: Int?
  get() =
    when (this) {
      PreviewQualityPolicy.Full -> null
      is PreviewQualityPolicy.CapHeight -> heightPx
    }

/**
 * An edit lowered at a requested height, together with the natural frame the height was applied against.
 *
 * @property resolved The graph to render, which is the graph an export at [spec] would run.
 * @property verdict What the export engine said about the edit, which carries its parity.
 * @property naturalSize The frame an export of the edit writes.
 * @property spec What [resolved] was lowered against, carrying the cap when there is one.
 * @property layoutSize The frame text lays out against. [naturalSize] under a cap, so a caption wraps on the same words
 * at either size, and null without one.
 */
internal class HeightLowering(
  val resolved: ResolvedComposition,
  val verdict: Verdict,
  val naturalSize: Size,
  val spec: ExportSpec,
  val layoutSize: Size?,
)

/**
 * Lowers [composition] through [resolve], rendering no taller than [heightPx] allows.
 *
 * The natural frame is settled first and the height applied against it under [cappedHeight]. A height that moves
 * nothing lowers once, and one that does lowers again against the natural frame as the one text lays out against.
 *
 * @return The lowering, or the first refusal [resolve] answered with.
 */
internal suspend fun lowerAtHeight(
  composition: EditComposition,
  heightPx: Int?,
  resolve: CompositionResolve,
): PlanResult<HeightLowering, FilmstripError> {
  val natural =
    when (val result = resolve(composition, ExportSpec(), null)) {
      is ResolveResult.Refused -> return PlanResult.Refused(result.error)
      is ResolveResult.Resolved -> result
    }

  val naturalSize = natural.composition.output.size
  val cap =
    cappedHeight(heightPx, naturalSize.height)
      ?: return PlanResult.Ready(HeightLowering(natural.composition, natural.verdict, naturalSize, ExportSpec(), null))

  val spec = ExportSpec(heightPx = cap)
  return when (val result = resolve(composition, spec, naturalSize)) {
    is ResolveResult.Refused -> {
      PlanResult.Refused(result.error)
    }
    is ResolveResult.Resolved -> {
      PlanResult.Ready(
        HeightLowering(result.composition, result.verdict, naturalSize, spec, naturalSize),
      )
    }
  }
}

/**
 * An edit lowered for the preview, together with what the preview reports about it.
 *
 * @property resolved The graph the preview builds, which is the graph an export of the same edit would run.
 * @property info What the preview delivers: the output frame, the preview-only scale and the parity the plan came back
 * with.
 * @property spec What [resolved] was lowered against, which carries whatever cap the policy asked for.
 * @property layoutSize The frame text lays out against, so a caption wraps on the same words under a cap as it does in
 * the export.
 */
internal class PreviewPlan(
  override val resolved: ResolvedComposition,
  override val info: PreviewInfo,
  val spec: ExportSpec,
  val layoutSize: Size?,
) : PlannedPreview

/**
 * Lowers [composition] for the preview, capping the rendered frame to whatever [policy] allows.
 *
 * The natural frame is settled first and the cap applied against it, so [PreviewInfo.renderScale] is a fraction of the
 * frame the export writes rather than of the frame the cap already produced. [fidelity] is the backend's own account of
 * how far its preview speaks for the exported file.
 *
 * @return The plan, or why the edit cannot be previewed.
 */
internal suspend fun planPreview(
  composition: EditComposition,
  policy: PreviewQualityPolicy,
  fidelity: List<FidelityNote>,
  resolve: CompositionResolve,
): PlanResult<PreviewPlan, PlaybackError> {
  val lowering =
    when (val result = lowerAtHeight(composition, policy.heightCap, resolve)) {
      is PlanResult.Refused -> return PlanResult.Refused(result.error.toPlaybackError())
      is PlanResult.Ready -> result.plan
    }

  return PlanResult.Ready(
    PreviewPlan(
      resolved = lowering.resolved,
      info =
        PreviewInfo(
          outputSize = lowering.naturalSize,
          renderScale =
            lowering.resolved.output.size.height
              .toFloat() / lowering.naturalSize.height,
          parity = lowering.verdict.parity(),
          parityNotes = lowering.verdict.parityNotes(),
          fidelity = fidelity,
        ),
      spec = lowering.spec,
      layoutSize = lowering.layoutSize,
    ),
  )
}

/**
 * The weakest parity across the plan's effects, or [EffectParity.Exact] when it names none.
 *
 * An incapable verdict reads the plan it would run without the effects it refused.
 */
internal fun Verdict.parity(): EffectParity = plan()?.parity ?: EffectParity.Exact

/**
 * The per-effect detail behind the plan's parity.
 *
 * Empty when the verdict carries no plan or no effect left a note.
 */
internal fun Verdict.parityNotes(): List<ParityNote> = plan()?.effectOrder?.mapNotNull { it.note }.orEmpty()

private fun Verdict.plan(): ExportPlan? =
  when (this) {
    is Verdict.Capable -> plan
    is Verdict.Degraded -> plan
    is Verdict.Incapable -> withoutUnsupported?.plan
  }

/**
 * What a failed lowering, or a step that throws while the preview draws, means to a player.
 *
 * A source that cannot be read or exported, a missing backend and a missing toolchain each keep their own meaning. A
 * platform failure, a full disk, an unwritable scratch file and a backgrounded app read as [PlaybackError.Underlying],
 * keeping the platform's code where there is one. Everything else is a composition the planner refused or an effect
 * that could not draw, which reads as an unsupported format whichever it was.
 */
internal fun FilmstripError.toPlaybackError(): PlaybackError =
  when (this) {
    is FilmstripError.SourceUnreadable -> {
      PlaybackError.SourceUnreadable(message)
    }
    is FilmstripError.SourceNotExportable -> {
      PlaybackError.SourceNotExportable(message)
    }
    is FilmstripError.BackendMissing -> {
      PlaybackError.BackendMissing(artifact, message)
    }
    is FilmstripError.ToolchainMissing -> {
      PlaybackError.DecoderUnavailable(message)
    }
    is FilmstripError.NoEncoder,
    is FilmstripError.UnsupportedResolution,
    is FilmstripError.DecoderRejectedInput,
    is FilmstripError.InvalidComposition,
    is FilmstripError.UnsupportedEffect,
    is FilmstripError.UnpreviewableEffect,
    is FilmstripError.PlatformEffectAsymmetry,
    is FilmstripError.UnsupportedStillFormat,
    -> {
      PlaybackError.UnsupportedFormat(message)
    }
    is FilmstripError.Underlying -> {
      PlaybackError.Underlying(platformCode, message)
    }
    is FilmstripError.InterruptedByBackgrounding,
    is FilmstripError.InsufficientStorage,
    is FilmstripError.SinkUnwritable,
    -> {
      PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, message)
    }
  }
