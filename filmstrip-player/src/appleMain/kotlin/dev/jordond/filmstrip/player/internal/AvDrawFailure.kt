package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.player.PlaybackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the preview item's chain could not draw, held as the preview's status until the next load since the item
 * itself plays on.
 *
 * [watch] hands the first failure a composition's chain reports to [onFailed] on [scope], once [error] holds it, as
 * long as that composition is still the one [current] gives when it lands. A failure from a composition a rebuild has
 * since replaced is dropped.
 *
 * @param current The composition the preview item draws through right now, or null when it has none.
 */
internal class AvDrawFailure(
  private val scope: CoroutineScope,
  private val current: () -> AvComposition?,
  private val onFailed: (PlaybackError) -> Unit,
) {
  /**
   * What the current chain could not draw, or null while it has drawn everything or since [clear].
   */
  var error: PlaybackError? = null
    private set

  /**
   * Drops what was held, the way a load does before it applies.
   */
  fun clear() {
    error = null
  }

  /**
   * Reports what [composition] fails to draw from here on.
   */
  fun watch(composition: AvComposition) {
    composition.onChainFailure = { failure ->
      scope.launch {
        if (current() === composition) {
          val reported = failure.toPlaybackError()
          error = reported
          onFailed(reported)
        }
      }
    }
  }
}
