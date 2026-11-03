package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlin.time.Duration

/**
 * The output frame rate this composition was lowered at, which is the grid every frame a preview shows sits on.
 *
 * Read off the frame rate the negotiation settled, which is the same number every backend lays its frames on.
 */
internal val ResolvedComposition.frameRate: Int
  get() = output.frameRate

/**
 * Where an exact seek to [at] lands, which is the time of the output slot [at] rounds to.
 *
 * The slot is clamped to the ones this composition covers, which run from zero up to, and not including, the slot its
 * duration lands on. A time before the start lands on the first frame and one at or past the end lands on the last.
 */
internal fun ResolvedComposition.seekLandingOf(at: Duration): Duration {
  val rate = frameRate
  val last = (outputSlotOf(duration, rate) - 1).coerceAtLeast(0)
  return outputSlotTime(outputSlotOf(at, rate).coerceIn(0, last), rate)
}

/**
 * Where a step of [frames] taken from [from] lands, or null when it would leave the composition.
 *
 * The step is counted on the output frame grid from the slot [from] lands on, so a step taken from between two frames
 * lands on one, and a run of single steps lands where one step of the whole count would.
 */
internal fun ResolvedComposition.frameStepLanding(
  from: Duration,
  frames: Int,
): Duration? {
  val rate = frameRate
  val landing = outputSlotTime(outputSlotOf(from, rate) + frames, rate)
  return landing.takeIf { it >= Duration.ZERO && it <= duration }
}
