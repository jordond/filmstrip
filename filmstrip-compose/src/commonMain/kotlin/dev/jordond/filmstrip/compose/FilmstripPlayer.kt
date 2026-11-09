package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PreviewFactory
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Remembers a player over [composition] and closes it when this leaves composition.
 *
 * The player is built once per [previews], with the [composition] and [config] it was first given.
 * Every later composition is loaded a frame after the composition that changed it, which is what leaves a
 * [PlaybackEventEffect] beside this one already collecting when the load starts emitting. An equal
 * composition costs nothing and a parameter-only edit does not rebuild the pipeline, so ordinary
 * editing needs no debounce here. A later [config] goes to the same player through its setters, so the transport and
 * the playhead stay.
 *
 * `remember` survives recomposition but not a configuration change. Hoist the player into something
 * that outlives the screen for anything longer-lived than a throwaway preview.
 *
 * @param previews What opens the player, usually the `Filmstrip` itself.
 * @param composition The edit to play.
 * @param config How the player should behave.
 * @return The remembered player, the same instance across recompositions.
 */
@Composable
public fun rememberFilmstripPlayer(
  previews: PreviewFactory,
  composition: Composition,
  config: PlayerConfig = PlayerConfig(),
): VideoPlayer {
  val player =
    remember(previews) {
      ClosingPlayerHolder(previews.preview(composition, config))
    }.player

  LaunchedEffect(player, config.qualityPolicy) { player.setQualityPolicy(config.qualityPolicy) }
  LaunchedEffect(player, config.exportSpec) { player.setExportSpec(config.exportSpec) }

  LaunchedEffect(player, composition) {
    withFrameNanos { }
    player.setComposition(composition)
  }

  return player
}
