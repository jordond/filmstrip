package dev.jordond.filmstrip.player

import androidx.compose.runtime.Stable
import dev.jordond.filmstrip.edit.EditComposition

/**
 * Opens a [VideoPlayer] over an [EditComposition].
 *
 * The part of [dev.jordond.filmstrip.Filmstrip] that plays an edit. A preview surface takes one of
 * these, and a [dev.jordond.filmstrip.Filmstrip] is one.
 */
@Stable
public interface PreviewFactory {
  /**
   * Opens a player over the same composition value that an export takes.
   *
   * Returns immediately. The composition loads asynchronously and progress is observable on the
   * player's own state. When no preview backend is registered, the returned player reports
   * [PlaybackError.BackendMissing] rather than throwing.
   *
   * @param composition The edit to play.
   * @param config How the player should behave.
   * @return A player, which the caller owns and must close.
   */
  public fun preview(
    composition: EditComposition,
    config: PlayerConfig = PlayerConfig(),
  ): VideoPlayer
}
