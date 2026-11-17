package dev.jordond.filmstrip.player.internal

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The slot a pause rested on, held as the floor of the playhead until the play that follows has taken media3's clock
 * to it.
 *
 * A paused player reads the slot of the frame it shows, and its clock can read either side of that frame. The slot
 * stays the reading once nothing names the frame any more, as when the surface goes, and through the replay of a held
 * sample, where the clock starts again from the sample's first slot.
 *
 * Read and written by whichever thread reads the position.
 *
 * @param resting The slot time of the frame on screen while the player is paused and one has been drawn since the last
 * seek, or null.
 * @param playWanted Whether the player was told to play. Only then does the clock take the playhead on from the floor.
 */
internal class Media3PlayheadFloor(
  private val resting: () -> Duration?,
  private val playWanted: () -> Boolean,
) {
  @Volatile
  private var slot: Duration? = null

  /**
   * What the playhead reads where media3's clock reads [clockMs], which is [clock] on the timeline.
   */
  fun read(
    clockMs: Long,
    clock: Duration,
  ): Duration {
    resting()?.let { rested ->
      slot = rested
      return rested
    }
    val floor = slot ?: return clock
    // A paused player's clock can read past the frame it shows, and the frame is the one the playhead is on.
    if (!playWanted()) return floor
    // Let go once media3's own clock has reached it. A slot is seldom a whole millisecond, so the clock is held to the
    // slot's own time and not to the millisecond under it, which the clock reads before it has reached the slot. A
    // clock read as the landing it rests on stands for a slot the player is about to play on from, and so is not one
    // that has.
    if (clockMs.milliseconds >= floor) slot = null
    return maxOf(clock, floor)
  }

  /**
   * Holds [to] as the floor where it is past the one held, as a replay does for the slot it was asked for.
   */
  fun raiseTo(to: Duration) {
    slot = maxOf(slot ?: to, to)
  }

  /**
   * Lets go of the floor, as a seek or a new graph moves the playhead.
   */
  fun clear() {
    slot = null
  }
}
