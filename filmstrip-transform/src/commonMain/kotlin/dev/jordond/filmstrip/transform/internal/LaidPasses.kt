package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.time.Duration

// The repeat schedule, derived once. The planner lays every pass from here and hands the backends a
// finished list, so no backend works out for itself how many times a track repeats or where the run
// stops. Four backends each counting their own passes is how one bed ends up four lengths long on
// one engine and one length long on another.

/**
 * The most passes one track may lay. A guard the planner holds every track to, not a limit any backend publishes.
 *
 * A pass is an ffmpeg split branch, an AVFoundation time range insertion, an item in a media3 sequence, or a browser
 * source node scheduled for its slot. The count is read per track, so a composition of several tracks may lay this
 * many on each of them.
 */
@InternalFilmstripApi
public const val MAX_LAID_PASSES: Int = 2_000

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
 * How much of the composition one track covers, counted from the track's own start.
 *
 * A looping track runs from where it starts to the end of the composition, and one laid down once runs the sum of its
 * own [lengths]. This is what [passesCovering] fills, derived here so a backend, the planner and a test all read the
 * same run rather than each working out where a track stops.
 *
 * @param duration How long the whole composition runs.
 */
@InternalFilmstripApi
public fun runLengthOf(
  looping: Boolean,
  start: Duration,
  lengths: List<Duration>,
  duration: Duration,
): Duration =
  if (looping) {
    maxOf(duration - start, Duration.ZERO)
  } else {
    lengths.fold(Duration.ZERO, Duration::plus)
  }

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

/**
 * How many passes [passesCovering] would lay over the same run, worked out from [lengths] rather than by laying them.
 *
 * Each clip of the run opens one pass per turn that still begins before [fill], counted from where the clip sits
 * inside the run. A count past [Int.MAX_VALUE] is reported as [Int.MAX_VALUE], which is over any ceiling a caller
 * reads it against.
 */
@InternalFilmstripApi
public fun passCountCovering(
  lengths: List<Duration>,
  fill: Duration,
): Int {
  val run = lengths.fold(Duration.ZERO, Duration::plus)
  if (run <= Duration.ZERO || fill <= Duration.ZERO) return 0

  val runNanos = run.inWholeNanoseconds
  val most = Int.MAX_VALUE.toLong()
  var opening = Duration.ZERO
  var count = 0L
  lengths.forEach { length ->
    val room = (fill - opening).inWholeNanoseconds
    if (room > 0) {
      val turns = ((room - 1) / runNanos + 1).coerceAtMost(most)
      count = (count + turns).coerceAtMost(most)
    }
    opening += length
  }
  return count.toInt()
}
