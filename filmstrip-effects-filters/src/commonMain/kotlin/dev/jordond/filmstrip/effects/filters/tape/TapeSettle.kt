package dev.jordond.filmstrip.effects.filters.tape

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.motion.Easing
import kotlin.time.Duration

/**
 * How a [TapeLook] settles, the way a deck does when it locks onto a recording.
 *
 * The look starts at the strength [from] on the first frame of the scope it is added to, which is the clip's slot for a
 * look on a clip and the composition for a look on the composition. Its [TapeLook.intensity] then eases to the strength
 * the look has everywhere after. Every figure of the look follows that one level together, the softness, the halo, the
 * jitter, the wave, the noise, the dropouts and event chances, the loss of colour, the lift and the tracking band. Two
 * of them follow it in their own way. The colour a tracking band takes is its whole loss at the strength the look
 * settles to, or at the strength it starts from when it settles to nothing, and less below that, in proportion. An
 * event band whose roll lies between the chance at the start and the chance at the end fades in or out along the
 * settle, so it does not switch on or off bar in a settle of a few ticks, and is whole or gone where the settle ends,
 * so after it the bands are the plain look's. A tracking band that rolls keeps its own place and its own
 * [Tracking.Rolling.settle], and runs at the strength the look has.
 *
 * The look runs wherever it is added, so a settle on a track starts again on every clip of the track and on every lap
 * of a looping track, since each is a run of its own with a first frame. A settle that should happen once belongs on
 * the clip or the composition that holds the first frame.
 *
 * A [TapeClock.Wall] preview draws its pattern and its band from its own clock, which has no start to count from, and
 * settles on the frame's own time, so it draws the look at the strength an export has there.
 *
 * @property from The strength the look starts at, from `0f` which leaves the frame alone to `1f`.
 * @property over How long the look takes to ease from [from] to its intensity.
 * @property easing How the ease is paced between its two ends.
 * @throws IllegalArgumentException when [from] is outside `0f..1f`, or [over] is not positive and finite.
 */
@Poko
public class TapeSettle(
  public val from: Float,
  public val over: Duration,
  public val easing: Easing = Easing.EaseInOut,
) {
  init {
    require(from in 0f..1f) { "A tape settle has to start within 0 to 1, not $from." }
    require(over.isPositive() && over.isFinite()) { "A tape settle has to be positive, not $over." }
  }
}
