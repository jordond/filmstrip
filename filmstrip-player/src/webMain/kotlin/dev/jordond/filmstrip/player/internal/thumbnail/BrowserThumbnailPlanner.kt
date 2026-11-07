package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.internal.HeightLowering
import dev.jordond.filmstrip.player.internal.NO_VIDEO_DECODER
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.lowerAtHeight
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.webcodecs.internal.preview.browserCanDecodeVideo
import dev.jordond.filmstrip.webcodecs.webCodecsExportEngine

/**
 * Lowers a thumbnail request through the same WebCodecs engine an export of it runs on.
 *
 * The lowering is [lowerAtHeight], so a strip frame is capped against the natural frame by the same
 * rule a capped preview is, and lays its text out against that natural frame.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class BrowserThumbnailPlanner(
  components: FilmstripComponents,
) {
  private val engine = webCodecsExportEngine(components)

  /**
   * Lowers [request] at its own height, or answers with the first refusal the engine gave.
   *
   * A page with no `VideoDecoder` can't draw a frame, so it refuses by name before a source is probed.
   */
  suspend fun lower(request: ThumbnailRequest): PlanResult<HeightLowering, FilmstripError> {
    if (!browserCanDecodeVideo()) return PlanResult.Refused(FilmstripError.InvalidComposition(NO_VIDEO_DECODER))
    return lowerAtHeight(
      request.composition,
      request.heightPx,
      request.exportSpec,
      engine::exportGrade,
      engine::resolve,
    )
  }
}
