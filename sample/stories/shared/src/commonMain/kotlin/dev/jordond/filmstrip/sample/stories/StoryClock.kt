package dev.jordond.filmstrip.sample.stories

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the clip under the story is, read from whatever plays it.
 *
 * Each value is snapshot state, so a composable that reads one is redrawn when the player moves it.
 */
@Stable
internal class StoryClock(
  playhead: State<Duration>,
  duration: State<Duration>,
  playing: State<Boolean>,
) {
  /**
   * Where the preview is on the story's timeline.
   */
  val playhead: Duration by playhead

  /**
   * How long the story runs, zero until the clip has been read.
   */
  val duration: Duration by duration

  val playing: Boolean by playing

  companion object {
    /**
     * A clock for a story with nothing playing under it, stopped at the start.
     */
    val Stopped: StoryClock = still()

    /**
     * A clock that never moves, stopped at the start of a story [duration] long.
     */
    fun still(duration: Duration = Duration.ZERO): StoryClock =
      StoryClock(mutableStateOf(Duration.ZERO), mutableStateOf(duration), mutableStateOf(false))
  }
}

/**
 * The clock of [player], following its position and its state for as long as this is in composition.
 */
@Composable
internal fun rememberStoryClock(player: VideoPlayer): StoryClock {
  val playhead = remember(player) { player.positionFlow(PLAYHEAD_TICK) }.collectAsState(Duration.ZERO)
  val state = player.state.collectAsState()
  return remember(player) {
    StoryClock(
      playhead = playhead,
      duration = derivedStateOf { state.value.duration ?: Duration.ZERO },
      playing = derivedStateOf { state.value.isPlaying },
    )
  }
}

private val PLAYHEAD_TICK = 33.milliseconds
