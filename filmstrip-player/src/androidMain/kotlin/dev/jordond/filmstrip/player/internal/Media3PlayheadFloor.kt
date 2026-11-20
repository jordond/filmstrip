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
 * sample, where the clock starts again from the sample's first slot. A play lets go of it once the clock has reached
 * it, read while it plays or after it stopped, since a short play can stop before anything reads the playhead.
 *
 * The slot a replay was for is kept apart from the floor until a seek or a new graph moves the playhead, whatever the
 * clock reads, since a paused player can still show the sample the replay went back for. See [replayed].
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

  @Volatile
  private var played = false

  @Volatile
  private var replay: Duration? = null

  /**
   * What the playhead reads where media3's clock reads [clockMs], which is [clock] on the timeline.
   */
  fun read(
    clockMs: Long,
    clock: Duration,
  ): Duration {
    resting()?.let { rested ->
      slot = rested
      played = false
      return rested
    }
    val floor = slot ?: return clock
    // A paused player's clock can read past the frame it shows, and the frame is the one the playhead is on. Once a
    // play has followed, a clock past the frame is where that play took it.
    if (!playWanted()) {
      if (!played || clockMs.milliseconds < floor) return floor
      clear()
      return clock
    }
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
    replay = to
  }

  /**
   * The slot the last replay was for, or null where a seek or a new graph has moved the playhead since.
   *
   * A replay goes back to the first slot of the sample the pause rested a later slot of. Until a later sample is drawn,
   * the frame on the surface is that sample, and it stands for this slot.
   */
  fun replayed(): Duration? = replay

  /**
   * Hears that the player was told to play, so a clock read past the floor from now on is one the play reached.
   */
  fun played() {
    played = true
  }

  /**
   * Lets go of the floor, as a seek or a new graph moves the playhead.
   */
  fun clear() {
    slot = null
    played = false
    replay = null
  }
}
