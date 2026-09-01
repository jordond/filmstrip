package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Built by [rememberScrubState], either over a [VideoPlayer] or over a host's own transport.
 */
@Stable
public class ScrubState internal constructor(
  private val onBegin: () -> Unit,
  private val onSeek: (Duration) -> Unit,
  private val onEnd: () -> Unit,
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
    onBegin()
  }

  /**
   * Moves the playhead to [position]. Ignored outside a gesture.
   */
  public fun onScrubTo(position: Duration) {
    if (!isScrubbing) return
    onSeek(position)
  }

  /**
   * Leaves scrubbing mode and settles on the frame the gesture ended on.
   */
  public fun onScrubEnd() {
    if (!isScrubbing) return
    isScrubbing = false
    onEnd()
  }
}

/**
 * Remembers the scrub protocol for [player].
 *
 * @param player The player the gesture drives.
 * @return State keyed to [player], the same instance across recompositions.
 */
@Composable
public fun rememberScrubState(player: VideoPlayer): ScrubState =
  remember(player) { ScrubState(player::beginScrub, player::seekTo, player::endScrub) }

/**
 * Remembers the scrub protocol for a transport the host drives itself.
 *
 * For a host seeking something other than a [VideoPlayer], such as a preview it renders on its own
 * clock. [onBegin] and [onEnd] are where a host relaxes and restores its own seek accuracy, and a
 * host with nothing to relax leaves them out.
 *
 * ```
 * val scrub = rememberScrubState(onSeek = { player.seekTo(it) })
 * ```
 *
 * @param onSeek Called with the position the gesture is over, on every delta.
 * @param onBegin Called once as the gesture starts.
 * @param onEnd Called once as the gesture ends, after the last [onSeek].
 * @return State that is the same instance across recompositions, calling whichever callbacks the
 *   last composition passed.
 */
@Composable
public fun rememberScrubState(
  onSeek: (Duration) -> Unit,
  onBegin: () -> Unit = {},
  onEnd: () -> Unit = {},
): ScrubState {
  val currentSeek by rememberUpdatedState(onSeek)
  val currentBegin by rememberUpdatedState(onBegin)
  val currentEnd by rememberUpdatedState(onEnd)

  return remember { ScrubState({ currentBegin() }, { currentSeek(it) }, { currentEnd() }) }
}
