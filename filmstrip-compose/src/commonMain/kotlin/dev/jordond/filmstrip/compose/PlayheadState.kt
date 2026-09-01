package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The playhead and the length it runs against, for a scrubber to draw.
 *
 * Built by [rememberPlayheadState], which keeps it fed from the player.
 */
@Stable
public class PlayheadState internal constructor() {
  /**
   * Where the playhead is, on the tick grid it was collected at.
   */
  public var position: Duration by mutableStateOf(Duration.ZERO)
    private set

  /**
   * The loaded composition's length, or null while nothing is loaded.
   */
  public var duration: Duration? by mutableStateOf(null)
    private set

  /**
   * [position] over [duration] in `0f..1f`, and zero while the length is unknown.
   */
  public val progress: Float
    get() {
      val total = duration ?: return 0f
      if (total <= Duration.ZERO) return 0f
      return (position / total).toFloat().coerceIn(0f, 1f)
    }

  private val provider: () -> Duration = { position }

  /**
   * Hands the playhead out as a lambda, to be called from a layout or draw lambda.
   *
   * `Modifier.offset { }` and `Modifier.graphicsLayer { }` re-run their lambda without recomposing,
   * so a thumb positioned from one of those follows the playhead for the cost of a layout pass.
   * Calling this in a composable body instead reads [position] during composition and recomposes on
   * every tick, which is the whole thing it exists to avoid.
   *
   * The same lambda comes back every call, so calling this inline in a composable body hands the
   * composable a parameter it can compare and skip on.
   *
   * @return a lambda reading the current position.
   */
  public fun positionProvider(): () -> Duration = provider

  internal fun onPosition(position: Duration) {
    this.position = position
  }

  internal fun onDuration(duration: Duration?) {
    this.duration = duration
  }
}

/**
 * Defaults for [rememberPlayheadState].
 */
public object PlayheadDefaults {
  /**
   * How often a playhead is worth reading for a UI, which is far below the frame rate.
   */
  public val Tick: Duration = 100.milliseconds
}

/**
 * Follows [player]'s playhead and the length of what it is playing.
 *
 * @param player The player to follow.
 * @param tick How often the playhead is read.
 * @return State keyed to [player], the same instance across recompositions.
 */
@Composable
public fun rememberPlayheadState(
  player: VideoPlayer,
  tick: Duration = PlayheadDefaults.Tick,
): PlayheadState {
  val state = remember(player) { PlayheadState() }

  LaunchedEffect(player, state, tick) {
    player.positionFlow(tick).collect { position -> state.onPosition(position) }
  }

  LaunchedEffect(player, state) {
    player.state.collect { snapshot -> state.onDuration(snapshot.duration) }
  }

  return state
}
