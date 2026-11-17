package dev.jordond.filmstrip.player.internal

import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The playhead a media3 reading of [positionMs] reports, given the slot time [landing] the last seek was aimed at.
 *
 * A seek reaches media3 floored to a whole millisecond by [toMediaMs], so a player resting on the slot it landed on
 * reads up to a millisecond before that slot's time. A reading of exactly that floored millisecond reports [landing]
 * itself, and every other reading is reported as media3 gave it. [mediaLanding] is the slot media3 was handed when that
 * is not [landing], as it is for a later slot of a held sample.
 */
internal fun playheadOf(
  positionMs: Long,
  landing: Duration?,
  mediaLanding: Duration? = landing,
): Duration = landing?.takeIf { (mediaLanding ?: it).toMediaMs() == positionMs } ?: positionMs.milliseconds

/**
 * Whether media3 will skip a seek of this player to [positionMs] and draw nothing for it.
 *
 * media3 does nothing for a seek to the millisecond a prepared player already rests on. A playing one keeps drawing
 * either way, so only a paused player is asked.
 */
internal fun Player.skipsSeekTo(positionMs: Long): Boolean {
  val prepared = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
  return prepared && !isPlaying && currentPosition == positionMs
}
