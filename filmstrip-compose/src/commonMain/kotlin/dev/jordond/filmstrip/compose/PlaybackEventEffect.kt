package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Collects [player]'s events for as long as this stays in composition.
 *
 * The only correct way to observe events from Compose. [VideoPlayer.events] replays nothing, so an
 * event emitted while nothing is collecting is gone for good, and a hand-rolled `LaunchedEffect`
 * races whatever command produced it.
 *
 * A new [onEvent] on recomposition is swapped in without restarting the subscription, so an event
 * cannot slip through the gap.
 *
 * @param player The player to observe.
 * @param onEvent Receives each event, on the collecting coroutine.
 */
@Composable
public fun PlaybackEventEffect(
  player: VideoPlayer,
  onEvent: (PlaybackEvent) -> Unit,
) {
  val current by rememberUpdatedState(onEvent)

  LaunchedEffect(player) {
    player.events.collect { event -> current(event) }
  }
}
