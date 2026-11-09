package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.registeredFfmpegEngine
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lowers an edit through the same ffmpeg engine an export of it runs on, and opens the pump on it.
 *
 * The engine is the one the host's own `ffmpegBackend` registration builds, so the preview runs the
 * binaries that registration names and lowers through the resolvers and probers an export does. ffmpeg
 * is the one backend whose engine carries a config, so it is read off the registration. Without a
 * registration the edit is refused as [MISSING_FFMPEG].
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal open class FfmpegPreviewPlanner(
  components: FilmstripComponents,
) : PreviewPlanner<PreviewPlan> {
  private val engine = components.registeredFfmpegEngine()

  final override suspend fun plan(
    composition: Composition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<PreviewPlan, PlaybackError> {
    val engine = engine ?: return PlanResult.Refused(MISSING_FFMPEG.toPlaybackError())
    // What the preview says about the grade follows the grade the plan settles on, so the notes go on once it has.
    return when (
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
        val plan = result.plan
        val info = plan.info
        val fidelity = ffmpegFidelity(plan.resolved.hdr)
        PlanResult.Ready(
          PreviewPlan(
            plan.resolved,
            PreviewInfo(info.outputSize, info.renderScale, info.parity, info.parityNotes, fidelity, info.inertNotices),
            plan.spec,
            plan.layoutSize,
          ),
        )
      }
    }
  }

  /**
   * Spawns a pump for [composition] against the frame [plan] settled on, starting at [at].
   *
   * The spec, the layout frame and the grade come off the plan, so the process runs the graph the plan
   * reported.
   */
  open suspend fun open(
    plan: PreviewPlan,
    composition: Composition,
    at: Duration,
  ): PreviewStreamResult =
    engine?.openPreview(composition, plan.spec, plan.layoutSize, at, plan.resolved.hdr)
      ?: PreviewStreamResult.Refused(MISSING_FFMPEG)
}

/**
 * Why an edit cannot be lowered on a `Filmstrip` with no ffmpeg backend registered.
 *
 * Export answers the same way, so preview and export never run against different toolchains.
 */
internal val MISSING_FFMPEG: FilmstripError.BackendMissing =
  FilmstripError.BackendMissing(
    artifact = FFMPEG_ARTIFACT,
    message =
      "The desktop preview runs on ffmpeg. Add $FFMPEG_ARTIFACT and register it with " +
        "Filmstrip { ffmpegBackend() }.",
  )

private const val FFMPEG_ARTIFACT = "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"

/**
 * How much the ffmpeg preview says about each property of a file exported under [hdr].
 *
 * The preview and the export run one `-filter_complex` over the same decoded frames, so everything
 * the chain decides is exact. What the preview cannot answer for is what happens after it: the
 * encoder it never runs, and a kept grade the pump converts straight to RGB rather than handing to a
 * display. A tone mapped export runs the shared curve in that same chain, so the pump shows what the
 * file holds.
 */
internal fun ffmpegFidelity(hdr: ResolvedHdr): List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
    when (hdr) {
      ResolvedHdr.Keep -> KEPT_GRADE
      ResolvedHdr.ToneMap -> TONE_MAPPED
    },
    FidelityNote(
      property = OutputProperty.Smoothness,
      fidelity = PreviewFidelity.Approximate,
      message = "Frames are paced as the decoder delivers them, and one that arrives late is late here only.",
    ),
  )

private val KEPT_GRADE =
  FidelityNote(
    property = OutputProperty.HdrAppearance,
    fidelity = PreviewFidelity.NotPreviewable,
    message = "The pump converts a grade straight to RGB, so an HDR export looks nothing like this.",
  )

private val TONE_MAPPED =
  FidelityNote(
    property = OutputProperty.HdrAppearance,
    fidelity = PreviewFidelity.Exact,
    message = "The pump runs the tone curve the export does, so the SDR frame it shows is the one the file holds.",
  )

internal val PreviewPlan.frameStep: Duration get() = resolved.frameStep

internal val PreviewPlan.frameRate: Int get() = resolved.frameRate

/**
 * How long one frame of this graph's output occupies.
 *
 * One period of [frameRate], for a check that asks whether two positions sit within a frame of each
 * other rather than which slot either one is on.
 */
internal val ResolvedComposition.frameStep: Duration
  get() = 1.seconds / frameRate
