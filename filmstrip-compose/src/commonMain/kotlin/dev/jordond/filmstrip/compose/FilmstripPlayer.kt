package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Remembers a player over [composition] and closes it when this leaves composition.
 *
 * The player is built once per [filmstrip] and [config], with the [composition] it was first given.
 * Every later value is loaded a frame after the composition that changed it, which is what leaves a
 * [PlaybackEventEffect] beside this one already collecting when the load starts emitting. An equal
 * composition costs nothing and a parameter-only edit does not rebuild the pipeline, so ordinary
 * editing needs no debounce here.
 *
 * `remember` survives recomposition but not a configuration change. Hoist the player into something
 * that outlives the screen for anything longer-lived than a throwaway preview.
 *
 * @param filmstrip The instance the player is built from.
 * @param composition The edit to play.
 * @param config How the player should behave.
 * @return The remembered player, the same instance across recompositions.
 */
@Composable
public fun rememberFilmstripPlayer(
  filmstrip: Filmstrip,
  composition: EditComposition,
  config: PlayerConfig = PlayerConfig(),
): VideoPlayer {
  val player =
    remember(filmstrip, config) {
      ClosingPlayerHolder(filmstrip.preview(composition, config))
    }.player

  LaunchedEffect(player, composition) {
    withFrameNanos { }
    player.setComposition(composition)
  }

  return player
}
