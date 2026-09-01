package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.jordond.filmstrip.compose.PlayheadState
import dev.jordond.filmstrip.compose.ui.component.scrollStripTo
import dev.jordond.filmstrip.compose.ui.component.stripScrollPx
import dev.jordond.filmstrip.compose.ui.geometry.StripGrid
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.time.Duration

/**
 * Whether the strip is keeping the playhead on screen.
 *
 * Built by [rememberPlayheadFollow].
 */
@Stable
public class PlayheadFollow internal constructor() {
  /**
   * Whether the strip is scrolling itself to keep up with the playhead.
   */
  public var isEngaged: Boolean by mutableStateOf(true)
    private set

  /**
   * Starts following again.
   */
  public fun engage() {
    isEngaged = true
  }

  /**
   * Leaves the strip where the user put it.
   */
  public fun disengage() {
    isEngaged = false
  }

  /**
   * Where the strip sat after this last scrolled it, in content pixels.
   *
   * A scroll that does not land here came from somewhere else, which is the only signal that covers a wheel and a
   * trackpad as well as a finger.
   */
  internal var settledScrollPx: Float? = null
}

/**
 * Keeps the playhead on screen while [isPlaying], and gets out of the way once the user scrolls.
 *
 * Following stops the moment a finger drags the strip and starts again when playback next starts, so watching and
 * editing do not fight over the scroll position. The strip is only scrolled once the playhead leaves the middle of the
 * viewport, which is what stops it jittering on every tick.
 *
 * ```
 * val follow = rememberPlayheadFollow(
 *   position = playhead.positionProvider(),
 *   grid = timeline.grid,
 *   state = timeline.listState,
 *   isPlaying = playerState.isPlaying,
 * )
 *
 * Box(Modifier.height(FilmstripTimelineDefaults.StripHeight)) {
 *   FilmstripStrip(frames = frames, grid = timeline.grid, state = timeline.listState)
 *
 *   if (!follow.isEngaged) {
 *     IconButton(
 *       onClick = follow::engage,
 *       modifier = Modifier.align(Alignment.TopEnd),
 *     ) { Icon(Recentre, "Follow the playhead") }
 *   }
 * }
 * ```
 *
 * @param position Where the playhead sits, from [PlayheadState.positionProvider] or from whatever clock the host drives
 * its own transport with.
 * @param grid Where the tiles sit, which is what turns a source time into a scroll offset.
 * @param state The strip's scroll state.
 * @param isPlaying Whether the player is advancing, from `PlayerState.isPlaying` .
 * @param enabled Whether the strip follows at all. False leaves the scroll position entirely to the host, and
 * [PlayheadFollow.isEngaged] stays wherever it was put.
 * @param sourceOffset Where the player's zero sits on the timeline's clock, so
 * `timelineTime == playerTime + sourceOffset()` .
 * @return State keyed to [state], the same instance across recompositions.
 */
@Composable
public fun rememberPlayheadFollow(
  position: () -> Duration,
  grid: StripGrid,
  state: LazyListState,
  isPlaying: Boolean,
  enabled: Boolean = true,
  sourceOffset: () -> Duration = { Duration.ZERO },
): PlayheadFollow {
  val follow = remember(state) { PlayheadFollow() }
  val currentPosition by rememberUpdatedState(position)
  val currentSourceOffset by rememberUpdatedState(sourceOffset)

  LaunchedEffect(follow, grid, state, enabled) {
    if (!enabled) return@LaunchedEffect
    snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
      .drop(1)
      .collect {
        if (!follow.isEngaged) return@collect
        val settled = follow.settledScrollPx
        if (settled == null || abs(state.stripScrollPx(grid) - settled) > SCROLL_SLACK) {
          follow.disengage()
        }
      }
  }

  LaunchedEffect(follow, isPlaying, enabled) {
    if (isPlaying && enabled) follow.engage()
  }

  LaunchedEffect(follow, grid, state, enabled) {
    if (!enabled) return@LaunchedEffect
    snapshotFlow { currentPosition() }.collect { position ->
      if (!follow.isEngaged || state.isScrollInProgress || grid.count == 0) return@collect

      val viewport =
        state.layoutInfo.viewportSize.width
          .toFloat()
      if (viewport <= 0f) return@collect

      val contentX = grid.scale.xOf(currentSourceOffset() + position)
      val viewportX = contentX - state.stripScrollPx(grid)
      if (viewportX >= viewport * BAND_START && viewportX <= viewport * BAND_END) return@collect

      state.scrollStripTo(grid, contentX - viewport / 2f)

      // Recorded after the scroll rather than before it, so a target the list clamped at either end
      // still matches where the strip actually came to rest.
      follow.settledScrollPx = state.stripScrollPx(grid)
    }
  }

  return follow
}

/**
 * How far the strip may sit from where this last put it and still count as untouched.
 */
private const val SCROLL_SLACK = 1f

/**
 * The part of the viewport the playhead may sit in before the strip scrolls to recentre it.
 */
private const val BAND_START = 0.25f
private const val BAND_END = 0.75f
