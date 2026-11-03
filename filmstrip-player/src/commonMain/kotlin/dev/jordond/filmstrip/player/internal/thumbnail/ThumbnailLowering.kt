package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PlannedPlayerEngine
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a thumbnail's lowering, refusing a throw as [FilmstripError.Underlying] so the request still gets an answer.
 *
 * Lowering reaches a prober, a resolver, a device query and a platform lowering, any of which may throw rather than
 * refuse. A thumbnail source answers its callback from the refusal this returns, the way a player engine answers a load
 * through [PlannedPlayerEngine.setComposition]. A cancellation is let through, since the handle that cancelled the
 * request has already stopped its callback.
 *
 * @param lower Lowers the request.
 * @return What [lower] returned, or the refusal its throw became.
 */
internal suspend inline fun <P> lowerThumbnail(
  lower: () -> PlanResult<P, FilmstripError>,
): PlanResult<P, FilmstripError> =
  try {
    lower()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (
    @Suppress("TooGenericExceptionCaught") broken: Exception,
  ) {
    PlanResult.Refused(
      FilmstripError.Underlying(
        FilmstripError.Underlying.NO_PLATFORM_CODE,
        broken.message ?: broken.toString(),
      ),
    )
  }
