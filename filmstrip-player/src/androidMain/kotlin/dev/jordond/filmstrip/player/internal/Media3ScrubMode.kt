package dev.jordond.filmstrip.player.internal

import android.os.Handler

/**
 * Whether media3 is in its scrubbing mode, which a scrub and a shuttle both put it in.
 *
 * A shuttle is a run of exact seeks like a scrub is, and scrubbing mode drops the audio it has to mute. It is
 * orthogonal to accuracy and free: it raises the codec operating rate and skips the flush a seek would otherwise cost,
 * which is what makes a burst of exact seeks affordable.
 *
 * @param handler Runs the change on the player's thread.
 * @param setEnabled Puts the player standing now in the mode or takes it out, on the player's thread.
 */
internal class Media3ScrubMode(
  private val handler: Handler,
  private val setEnabled: (Boolean) -> Unit,
) {
  @Volatile
  private var scrubbing = false

  @Volatile
  private var shuttling = false

  /**
   * Whether a scrub or a shuttle is under way.
   */
  val active: Boolean get() = scrubbing || shuttling

  fun scrubbingChanged(scrubbing: Boolean) {
    this.scrubbing = scrubbing
    apply()
  }

  fun shuttlingChanged(shuttling: Boolean) {
    this.shuttling = shuttling
    apply()
  }

  /**
   * Puts a player that has just replaced the one standing in the mode a scrub or a shuttle under way asked for. A fresh
   * player starts out of it. Called on the player's thread.
   */
  fun carry() {
    if (active) setEnabled(true)
  }

  private fun apply() {
    val enabled = active
    handler.post { setEnabled(enabled) }
  }
}
