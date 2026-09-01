package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.paced
import kotlin.time.Duration

/**
 * Computes the region this pan shows at [time], as a fraction of the frame entering it.
 *
 * Every backend that draws a pan reads its region from here rather than interpolating one of its
 * own, which is what keeps two engines from putting the same clip in two different places at the
 * same composition time.
 *
 * [time] is composition-relative on every backend, so [span] is the clip's own slot on the
 * composition timeline. A time before the span holds [KenBurns.from] and a time after it holds
 * [KenBurns.to], so a frame landing a rounding error outside the slot draws the end it is nearest.
 *
 * @param time Where the frame sits on the composition's timeline.
 * @param span The clip's slot on that timeline.
 * @return The region that fills the frame at [time].
 */
@ExperimentalFilmstripApi
public fun KenBurns.regionAt(
  time: Duration,
  span: TimeRange,
): NormalizedRect {
  val eased = easing.paced(span.fractionAt(time))
  return NormalizedRect(
    left = interpolate(from.left, to.left, eased),
    top = interpolate(from.top, to.top, eased),
    right = interpolate(from.right, to.right, eased),
    bottom = interpolate(from.bottom, to.bottom, eased),
  )
}

// A span with no measurable length has no travel to be partway through, which is the trim that
// collapsed to a single frame and the open-ended range nothing has probed yet.
private fun TimeRange.fractionAt(time: Duration): Float {
  val length = duration ?: return 0f
  if (length <= Duration.ZERO) return 0f
  return ((time - start) / length).toFloat().coerceIn(0f, 1f)
}

private fun interpolate(
  from: Float,
  to: Float,
  fraction: Float,
): Float = from + (to - from) * fraction
