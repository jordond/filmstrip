package dev.jordond.filmstrip.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.drawnRangeOf
import dev.jordond.filmstrip.effects.overlay.overlayFrameWithin
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The playhead a stage draws its timed elements at, fed from the stage's player.
 *
 * The position and the duration are snapshot state, so a draw that reads [time] runs again as the playhead moves and
 * nothing recomposes. The player is only listened to while an element holds the clock through [retain].
 */
@Stable
internal class StageClock {
  private var player: VideoPlayer? = null
  private var positionNanos by mutableLongStateOf(0L)
  private var duration by mutableStateOf<Duration?>(null)

  var readers by mutableIntStateOf(0)
    private set

  /**
   * The composition's whole length, which is the span a composition level overlay is drawn over, or null while there
   * is no player or it has no duration yet.
   */
  fun span(): TimeRange? = duration?.takeIf { it > Duration.ZERO }?.let { TimeRange.of(Duration.ZERO, it) }

  /**
   * The player's position held inside [span], or null where [span] is.
   */
  fun time(): Duration? {
    val length = duration?.takeIf { it > Duration.ZERO } ?: return null
    // A player parked at the end reads the duration while it shows the last frame. The preview reports no frame rate,
    // so that frame's own time is unknown and the position is held just under the end instead, which is under one
    // frame away from it.
    return positionNanos.nanoseconds.coerceIn(Duration.ZERO, length - BELOW_END)
  }

  fun attach(player: VideoPlayer?) {
    if (this.player === player) return
    this.player = player
    if (readers > 0) read()
  }

  // The first reader takes a reading on the spot, so the frame it is first drawn on is already at the playhead.
  fun retain() {
    if (readers++ == 0) read()
  }

  fun release() {
    readers--
  }

  fun moveTo(position: Duration) {
    positionNanos = position.inWholeNanoseconds
  }

  fun lasts(duration: Duration?) {
    this.duration = duration
  }

  private fun read() {
    val player = player
    duration = player?.state?.value?.duration
    positionNanos = player?.currentPosition()?.inWholeNanoseconds ?: 0L
  }

  private companion object {
    val BELOW_END = 1.nanoseconds
  }
}

internal val LocalStageClock = compositionLocalOf<StageClock?> { null }

/**
 * Keeps [clock] on [player]'s playhead for as long as an element on the stage reads it.
 */
@Composable
internal fun StageClockFeed(
  player: VideoPlayer?,
  clock: StageClock,
) {
  SideEffect { clock.attach(player) }
  val read = clock.readers > 0
  LaunchedEffect(player, clock, read) {
    if (player == null || !read) return@LaunchedEffect
    launch { player.state.collect { clock.lasts(it.duration) } }
    player.positionFlow(Duration.ZERO).collect { clock.moveTo(it) }
  }
}

/**
 * What one element is drawn with at the stage's playhead, answered by the sampler an export draws its overlay with.
 *
 * [frame] is null while the playhead is outside the element's window. An element held still, or one on a stage whose
 * clock has no time, is drawn as authored at its own opacity. The values are snapshot state, so a draw reading
 * [frame] runs again when any of them or the playhead moves.
 */
@OptIn(InternalFilmstripApi::class)
@Stable
internal class ElementTiming(
  private val clock: StageClock?,
) {
  var still by mutableStateOf(true)
  var window by mutableStateOf<TimeRange?>(null)
  var animation by mutableStateOf<OverlayAnimation?>(null)
  var opacity by mutableFloatStateOf(1f)

  private val drawnRange by derivedStateOf { clock?.span()?.let { span -> drawnRangeOf(window, span) } }

  val frame: State<OverlayFrame?> =
    derivedStateOf {
      val range = drawnRange
      val time = if (still) null else clock?.time()
      if (range == null || time == null) {
        overlayFrameWithin(null, null, opacity, WHOLE, Duration.ZERO)
      } else {
        overlayFrameWithin(window, animation, opacity, range, time)
      }
    }

  val drawn: State<Boolean> = derivedStateOf { frame.value != null }

  private companion object {
    val WHOLE = TimeRange.from(Duration.ZERO)
  }
}

/**
 * Remembers the timing of one element on [clock] and holds the clock while the element has a window or an animation.
 */
@Composable
internal fun rememberElementTiming(
  clock: StageClock?,
  still: Boolean,
  opacity: Float,
  visibleDuring: TimeRange?,
  animation: OverlayAnimation?,
): ElementTiming {
  val timing = remember(clock) { ElementTiming(clock) }
  timing.still = still
  timing.opacity = opacity
  timing.window = visibleDuring
  timing.animation = animation
  if (clock != null && (visibleDuring != null || animation != null)) {
    DisposableEffect(clock) {
      clock.retain()
      onDispose { clock.release() }
    }
  }
  return timing
}
