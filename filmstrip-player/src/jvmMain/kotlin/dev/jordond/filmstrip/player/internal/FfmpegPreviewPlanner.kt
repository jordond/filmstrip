package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.FidelityNote
import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.registeredFfmpegEngine
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
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
    composition: EditComposition,
    policy: PreviewQualityPolicy,
  ): PlanResult<PreviewPlan, PlaybackError> =
    when (val engine = engine) {
      null -> PlanResult.Refused(MISSING_FFMPEG.toPlaybackError())
      else -> planPreview(composition, policy, FFMPEG_FIDELITY, engine::resolve)
    }

  /**
   * Spawns a pump for [composition] against the frame [plan] settled on, starting at [at].
   *
   * The spec and the layout frame come off the plan, so the process runs the graph the plan
   * reported.
   */
  open suspend fun open(
    plan: PreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult =
    engine?.openPreview(composition, plan.spec, plan.layoutSize, at) ?: PreviewStreamResult.Refused(MISSING_FFMPEG)
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
 * How much the ffmpeg preview says about each property of the exported file.
 *
 * The preview and the export run one `-filter_complex` over the same decoded frames, so everything
 * the chain decides is exact. What the preview cannot answer for is what happens after it: the
 * encoder it never runs, and a grade it converts straight to RGB rather than handing to a display.
 */
private val FFMPEG_FIDELITY: List<FidelityNote> =
  listOf(
    NO_ENCODER_PREVIEW,
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
