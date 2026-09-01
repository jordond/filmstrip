package dev.jordond.filmstrip.compose.ui.interaction

import kotlin.time.Duration

/**
 * The calls one held arrow key's seek makes, in order.
 *
 * Compose delivers a held key as many `KeyDown` repeats for one physical press. Opening and settling a scrub for
 * each of them would make the relaxed window `ScrubState` promises between a start and an end zero-length, turning
 * every repeat into its own exact, cancelling seek, the failure the scrub protocol exists to prevent. This starts
 * once, on the first repeat of a burst, and stays started across every later one until [end] is called.
 *
 * The target advances from itself rather than from a freshly read position, because the reported position is
 * deliberately behind while a scrub is relaxed: reading it again mid-burst would resume from wherever the player
 * last caught up to rather than from where the previous repeat left off.
 *
 * Split from the key handler that drives it for the same reason [ScrubGesture] is: the ordering is a plain state
 * machine rather than something only real key repeat can exercise.
 *
 * @param onStart Called once, as the first repeat of a burst begins a scrub.
 * @param onSeek Called with the accumulated target, for every repeat.
 * @param onEnd Called once, as the burst ends.
 */
internal class ArrowScrubGesture(
  private val onStart: () -> Unit,
  private val onSeek: (Duration) -> Unit,
  private val onEnd: () -> Unit,
) {
  private var isActive = false
  private var target: Duration = Duration.ZERO

  /**
   * Whether a burst is currently open.
   */
  val isEngaged: Boolean
    get() = isActive

  /**
   * Advances the target by [delta] and seeks to it, clamping through [clamp].
   *
   * [currentPosition] is read only to seed the target the first time this is called after [end] or construction, so
   * a later repeat in the same burst never re-reads a clock the scrub has not caught up to yet.
   */
  fun advance(
    delta: Duration,
    currentPosition: () -> Duration,
    clamp: (Duration) -> Duration,
  ) {
    if (!isActive) {
      isActive = true
      onStart()
      target = currentPosition()
    }

    target = clamp(target + delta)
    onSeek(target)
  }

  /**
   * Ends the burst. Repeating it, or calling it before the first [advance], does nothing.
   */
  fun end() {
    if (!isActive) return
    isActive = false
    onEnd()
  }
}
