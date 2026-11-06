package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.registeredFfmpegEngine
import dev.jordond.filmstrip.player.internal.HeightLowering
import dev.jordond.filmstrip.player.internal.MISSING_FFMPEG
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.lowerAtHeight
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import kotlin.time.Duration

/**
 * Lowers a thumbnail request through the same ffmpeg engine an export of it runs on.
 *
 * The lowering is [lowerAtHeight], so a strip frame is capped against the natural frame by the same
 * rule a capped preview is, and lays its text out against that natural frame.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class FfmpegThumbnailPlanner(
  components: FilmstripComponents,
) {
  private val engine = components.registeredFfmpegEngine()

  /**
   * Lowers [request] at its own height, or refuses it as [MISSING_FFMPEG] when no ffmpeg backend is registered.
   */
  suspend fun lower(request: ThumbnailRequest): PlanResult<HeightLowering, FilmstripError> =
    when (val engine = engine) {
      null -> {
        PlanResult.Refused(MISSING_FFMPEG)
      }
      else -> {
        lowerAtHeight(
          request.composition,
          request.heightPx,
          request.exportSpec,
          engine::exportGrade,
          engine::resolve,
        )
      }
    }

  /**
   * Spawns the pump for one frame of [composition], windowed at [at].
   */
  suspend fun open(
    plan: HeightLowering,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult =
    engine?.openPreview(composition, plan.spec, plan.layoutSize, at, plan.resolved.hdr)
      ?: PreviewStreamResult.Refused(MISSING_FFMPEG)
}
