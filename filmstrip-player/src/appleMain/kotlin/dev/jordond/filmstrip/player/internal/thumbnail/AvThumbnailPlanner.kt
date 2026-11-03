package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.avFoundationExportEngine
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.lowerAtHeight
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest

/**
 * Lowers a thumbnail request through the same AVFoundation engine an export of it runs on.
 *
 * The lowering is [lowerAtHeight], so a strip frame is capped against the natural frame by the same
 * rule a capped preview is, and lays its text out against that natural frame.
 *
 * @param components The components the owning `Filmstrip` was built with.
 */
internal class AvThumbnailPlanner(
  components: FilmstripComponents,
) {
  private val engine = avFoundationExportEngine(components)

  /**
   * Lowers [request] at its own height and builds the AVFoundation composition a generator renders from.
   *
   * A composition AVFoundation cannot build is refused as [FilmstripError.InvalidComposition].
   */
  suspend fun lower(request: ThumbnailRequest): PlanResult<AvComposition, FilmstripError> {
    val lowering =
      when (val result = lowerAtHeight(request.composition, request.heightPx, engine::resolve)) {
        is PlanResult.Refused -> return result
        is PlanResult.Ready -> result.plan
      }

    return try {
      PlanResult.Ready(lowering.resolved.toAvComposition())
    } catch (refused: AvLoweringFailure) {
      PlanResult.Refused(FilmstripError.InvalidComposition(refused.reason))
    }
  }
}
