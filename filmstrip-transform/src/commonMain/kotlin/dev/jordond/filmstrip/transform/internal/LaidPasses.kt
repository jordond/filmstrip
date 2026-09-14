package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.time.Duration

// The repeat schedule, derived once. The planner lays every pass from here and hands the backends a
// finished list, so no backend works out for itself how many times a track repeats or where the run
// stops. Four backends each counting their own passes is how one bed ends up four lengths long on
// one engine and one length long on another.

/**
 * One clip of a track, laid down at one place in the run.
 *
 * @property index Which entry of the lengths the schedule was derived from, so which clip of the
 *   track this plays.
 * @property offset Where this pass opens within the run, counted from the track's own start.
 * @property length How long this pass holds. Shorter than the clip's own length only on the last
 *   pass, cut where the run ends.
 */
@InternalFilmstripApi
public class LaidPass(
  public val index: Int,
  public val offset: Duration,
  public val length: Duration,
)

/**
 * The run described by [lengths] laid down enough times to cover [fill], with the last pass cut
 * where it runs past.
 *
 * One code path for both kinds of track. A track that does not loop passes the sum of its own
 * lengths and gets one uncut pass per clip in order, and a looping one passes the run it has to
 * cover. A run that adds up to nothing, or nothing to fill, lays nothing at all.
 */
@InternalFilmstripApi
public fun passesCovering(
  lengths: List<Duration>,
  fill: Duration,
): List<LaidPass> {
  val run = lengths.fold(Duration.ZERO, Duration::plus)
  // A run of no length would never reach the fill, so the walk below would never end.
  if (run <= Duration.ZERO || fill <= Duration.ZERO) return emptyList()

  val passes = mutableListOf<LaidPass>()
  var offset = Duration.ZERO
  while (offset < fill) {
    lengths.forEachIndexed { index, length ->
      if (offset < fill) {
        passes += LaidPass(index, offset, minOf(length, fill - offset))
        offset += length
      }
    }
  }
  return passes
}
