package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration

/**
 * The three calls a scrubbing gesture makes, in order.
 *
 * Call [onScrubStart] on touch down, [onScrubTo] on every drag delta and [onScrubEnd] on touch up.
 * Between the first and the last the engine relaxes seek accuracy to keep up with the finger, and
 * the last one settles on the exact frame under it. A gesture that only calls [onScrubTo] issues a
 * fresh exact seek per delta, each one cancelling the last, and the picture stops updating while
 * the finger moves.
 *
 * Built by [rememberScrubState].
 */
@Stable
public class ScrubState internal constructor(
  private val player: VideoPlayer,
) {
  /**
   * Whether a gesture is in flight.
   */
  public var isScrubbing: Boolean by mutableStateOf(false)
    private set

  /**
   * Enters scrubbing mode. Repeating it during a gesture does nothing.
   */
  public fun onScrubStart() {
    if (isScrubbing) return
    isScrubbing = true
    player.beginScrub()
  }

  /**
   * Moves the playhead to [position]. Ignored outside a gesture.
   */
  public fun onScrubTo(position: Duration) {
    if (!isScrubbing) return
    player.seekTo(position)
  }

  /**
   * Leaves scrubbing mode and settles on the frame the gesture ended on.
   */
  public fun onScrubEnd() {
    if (!isScrubbing) return
    isScrubbing = false
    player.endScrub()
  }
}

/**
 * Remembers the scrub protocol for [player].
 *
 * @param player The player the gesture drives.
 * @return State keyed to [player], the same instance across recompositions.
 */
@Composable
public fun rememberScrubState(player: VideoPlayer): ScrubState = remember(player) { ScrubState(player) }
