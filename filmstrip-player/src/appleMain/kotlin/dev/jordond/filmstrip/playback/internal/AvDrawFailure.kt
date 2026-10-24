package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.CoreImageChain
import dev.jordond.filmstrip.player.PlaybackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the preview item's chain could not draw, held as the preview's status until the next load since the item
 * itself plays on.
 *
 * [watch] hands the first failure a chain reports to [onFailed] on [scope], once [error] holds it, as long as that chain
 * is still the one [current] gives when it lands. A failure from a chain a rebuild has since replaced is dropped.
 *
 * @param current The chain the preview item draws through right now, or null when it has none.
 */
@OptIn(InternalFilmstripApi::class)
internal class AvDrawFailure(
  private val scope: CoroutineScope,
  private val current: () -> CoreImageChain?,
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
   * Reports what [chain] fails to draw from here on.
   */
  fun watch(chain: CoreImageChain) {
    chain.onFailure = { failure ->
      scope.launch {
        if (current() === chain) {
          val reported = failure.toPlaybackError()
          error = reported
          onFailed(reported)
        }
      }
    }
  }
}
