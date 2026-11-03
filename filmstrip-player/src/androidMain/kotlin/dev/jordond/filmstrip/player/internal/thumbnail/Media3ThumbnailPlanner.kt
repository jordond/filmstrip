package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media3.internal.bounds.readFrameBounds
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.media3.internal.preview.Media3Preview
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.media3.media3ExportEngine
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.lowerAtHeight
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest

/**
 * Lowers a thumbnail request through the same media3 engine an export of it runs on.
 *
 * The lowering is [lowerAtHeight], so a strip frame is capped against the natural frame by the same
 * rule a capped preview is, and lays its text out against that natural frame.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class Media3ThumbnailPlanner(
  components: FilmstripComponents,
) {
  private val engine = media3ExportEngine(components)

  /**
   * Lowers [request] at its own height and builds the media3 preview a frame extractor renders from.
   *
   * A composition media3 cannot build is refused as [FilmstripError.InvalidComposition].
   */
  suspend fun lower(request: ThumbnailRequest): PlanResult<Media3Preview, FilmstripError> {
    val lowering =
      when (val result = lowerAtHeight(request.composition, request.heightPx, engine::resolve)) {
        is PlanResult.Refused -> return result
        is PlanResult.Ready -> result.plan
      }

    val resolved = lowering.resolved
    return try {
      PlanResult.Ready(resolved.toMedia3Preview(resolved.readFrameBounds()))
    } catch (refused: Media3LoweringFailure) {
      PlanResult.Refused(FilmstripError.InvalidComposition(refused.reason))
    }
  }
}
