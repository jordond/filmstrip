package dev.jordond.filmstrip.player.internal

import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import kotlin.time.Duration

/**
 * The later slot of a held sample a preview that rests on one frame is pinned to, so whatever replays that frame draws
 * it on the slot the playhead reports.
 *
 * Everything here is read and written on the player's thread, the one owner of the pin, so no pause can be wiped by a
 * play or a seek asked for before it.
 *
 * @param player The player standing now.
 * @param frameRate The frame rate of the composition loaded now, or null before one is.
 * @param firstSlotOf The slot a sample shown on a slot is written on.
 * @param pinSlot Tells the preview which later slot of a held sample it rests on, or null for none.
 * @param drawn The slot time of the frame last handed to the surface, or null when none has been since the last seek.
 * Read whether the player plays or not.
 */
internal class Media3HeldPin(
  private val player: () -> Player?,
  private val frameRate: () -> Int?,
  private val firstSlotOf: (Duration) -> Duration = { it },
  private val pinSlot: (Duration?) -> Unit = {},
  private val drawn: () -> Duration? = { null },
) {
  /**
   * The later slot of a held sample the preview is pinned to, or null.
   */
  var slot: Duration? = null
    private set

  /**
   * Pins the preview to the slot [at] lands on where that is a later slot of a held sample, and lets go of it where it
   * is the first slot of its sample or [at] is null.
   */
  fun pin(at: Duration?) {
    pinSlot(at)
    val rate = frameRate()
    val onSlot = at?.let { if (rate == null) it else outputSlotTime(outputSlotOf(it, rate), rate) }
    slot = onSlot?.takeIf { firstSlotOf(it) != it }
  }

  /**
   * Pins the preview to [held], the slot [heldSlotOf] gave for a seek being handed to the player.
   *
   * @return Whether that moved the pin, so the seek has to be carried out even where the player already rests on the
   * sample.
   */
  fun moveTo(held: Duration?): Boolean {
    pinSlot(held)
    val moved = held != slot
    slot = held
    return moved
  }

  /**
   * Pins the preview to the slot of the frame on the surface, so a paused player draws the slot it reports again
   * whatever replays the frame. A playing player holds no pin, and neither does one that has drawn no frame. A frame
   * drawn before a seek handed over since is left to that seek, which pins the slot it is for.
   *
   * @param handled The serial of the last seek the playback thread had handled when the frame on the surface was
   * drawn.
   * @param handedOver The serial of the seek the player was handed last.
   */
  fun settle(
    handled: Int,
    handedOver: Int,
  ) {
    if (handled != handedOver || player()?.playWhenReady != false) return
    pin(drawn() ?: return)
  }

  /**
   * Hands [target], a player standing on [start] of a graph just built, the transport and pins it by the rule a seek
   * is pinned by. The pin follows the transport, since a player told to play says so and that lets go of the pin.
   *
   * @param wanted Whether the player this one replaces was told to play.
   * @param media The slot the sample shown on [start] is written on.
   * @param scrubbing Whether a scrub or a shuttle is under way.
   */
  fun start(
    target: Player,
    wanted: Boolean,
    start: Duration,
    media: Duration,
    scrubbing: Boolean,
  ) {
    target.playWhenReady = wanted
    pin(heldSlotOf(start, media, wanted, scrubbing))
  }

  /**
   * Lets go of the pin, as the player is about to play.
   */
  fun release() {
    pinSlot(null)
    slot = null
  }

  /**
   * Lets go of the slot a scrub or a shuttle pinned a player that was told to play to, as it ends and the player plays
   * on. Nothing else tells a player media3 stopped for a scrub that it plays again.
   */
  fun scrubEnded() {
    if (player()?.playWhenReady == true) release()
  }
}

/**
 * The slot a player sent to [target] is pinned to, or null where it holds no pin there.
 *
 * media3 reads [target] from [media], the slot its sample is written on, so [target] is a later slot of a held sample
 * where the two differ. A playing player draws every slot of a held sample itself, so only one that rests on the frame
 * it lands on is pinned. A paused player does, and so does one a scrub under way stops there.
 *
 * @param playWanted Whether the player was told to play.
 * @param scrubbing Whether a scrub or a shuttle is under way.
 */
internal fun heldSlotOf(
  target: Duration,
  media: Duration,
  playWanted: Boolean,
  scrubbing: Boolean,
): Duration? = target.takeIf { media != target && (!playWanted || scrubbing) }
