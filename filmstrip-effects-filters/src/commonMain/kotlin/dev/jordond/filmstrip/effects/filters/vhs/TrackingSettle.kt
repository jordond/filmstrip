package dev.jordond.filmstrip.effects.filters.vhs

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.motion.Easing
import kotlin.time.Duration

/**
 * How a [Tracking.Rolling] band eases off, from full strength at [Tracking.Rolling.at] after the first frame of the
 * scope the look is in to no band at all.
 *
 * The band's slide, streaky noise, colour fade and snow all fall together, and the band keeps rolling on its period
 * while they do. Before [Tracking.Rolling.at] the band is at full strength. The scope's first frame is the clip's for a
 * look on a clip, so the band settles again on every clip of a track and on every lap of a looping track. Once [over]
 * has passed the band is gone and does not come back, so a band that rolls once and is gone settles over one period. A
 * [TapeClock.Wall] preview draws from its own clock, which has no start to count from, and draws the band gone.
 *
 * @property over How long the band takes to fall from full strength to nothing.
 * @property easing How the fall is paced between its two ends.
 * @throws IllegalArgumentException when [over] is not positive and finite.
 */
@Poko
public class TrackingSettle(
  public val over: Duration,
  public val easing: Easing = Easing.EaseInOut,
) {
  init {
    require(over.isPositive() && over.isFinite()) { "A tracking settle has to be positive, not $over." }
  }
}
