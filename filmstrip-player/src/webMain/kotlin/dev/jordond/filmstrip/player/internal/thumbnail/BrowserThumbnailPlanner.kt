package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.internal.HeightLowering
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.lowerAtHeight
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
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
   */
  suspend fun lower(request: ThumbnailRequest): PlanResult<HeightLowering, FilmstripError> =
    lowerAtHeight(request.composition, request.heightPx, request.exportSpec, engine::exportGrade, engine::resolve)
}
