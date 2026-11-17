package dev.jordond.filmstrip.player.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * What the playhead reads once the player has stopped.
 *
 * A paused player reads the slot of the frame on its surface. media3's clock reads a couple of slots behind what it has
 * drawn by the time it pauses, and the frame is what a caller who stops to look at it reads the position for. A shuttle
 * ends there too, on the frame on screen. A player that has ended reads the duration, whatever slot the last frame it
 * drew was on, and no reading is past the duration, which media3's clock runs a few milliseconds over as it ends.
 *
 * Each frame drawn while paused, each pause and the end push the reading, since none of them is a tick of the clock.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param renderedUs The stamp of the last frame media3 handed the surface, in microseconds on the composition's
 * timeline, or [NO_FRAME] where none has been since a load, a seek or a surface let go of.
 * @param isEnded Whether the player has reached the end of the composition and nothing has moved it since.
 * @param isPlayWanted Whether playback is wanted, read on the engine's dispatcher.
 * @param landingOf The slot time a time on the timeline lands on.
 * @param duration How long the composition loaded now runs.
 * @param playhead What the playhead reads while the player has not ended.
 * @param emitPosition Pushes one playhead reading.
 */
internal class Media3PausedFrame(
  private val scope: CoroutineScope,
  private val renderedUs: () -> Long,
  private val isEnded: () -> Boolean,
  private val isPlayWanted: () -> Boolean,
  private val landingOf: (Duration) -> Duration,
  private val duration: () -> Duration,
  private val playhead: () -> Duration,
  private val emitPosition: (Duration) -> Unit,
) {
  /**
   * The slot time of the frame last handed to the surface, or null when none has been since a load, a seek or a
   * surface let go of.
   */
  fun drawn(): Duration? = renderedUs().takeIf { it != NO_FRAME }?.let { landingOf(it.microseconds) }

  /**
   * The slot time of the frame on the surface while the player is paused, or null while it plays or has no frame.
   */
  fun resting(): Duration? = if (isPlayWanted()) null else drawn()

  /**
   * What the playhead reads now, which is the duration once the player has ended and never past it.
   */
  fun position(): Duration {
    val length = duration()
    return if (isEnded()) length else playhead().let { if (length > Duration.ZERO) minOf(it, length) else it }
  }

  /**
   * Pushes the playhead as the player ends, where it moves from the clock to the duration. Called on the engine's
   * dispatcher.
   */
  fun onEnded() {
    emitPosition(position())
  }

  /**
   * Pushes the playhead for a frame drawn while the player is paused. Called on media3's playback thread.
   */
  fun onFrame() {
    scope.launch { if (!isPlayWanted()) emitPosition(position()) }
  }

  /**
   * Pushes the playhead as the player pauses, where it moves from the clock to the frame on the surface.
   */
  fun onPaused() {
    scope.launch { emitPosition(position()) }
  }
}
