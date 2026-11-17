package dev.jordond.filmstrip.player.internal

import android.os.Handler

/**
 * Whether media3 is in its scrubbing mode, which a scrub and a shuttle both put it in.
 *
 * A shuttle is a run of exact seeks like a scrub is, and scrubbing mode drops the audio it has to mute. It is
 * orthogonal to accuracy and free: it raises the codec operating rate and skips the flush a seek would otherwise cost,
 * which is what makes a burst of exact seeks affordable.
 *
 * media3's limit: a player that has played since its last seek draws the wrong frame for a seek handed over in
 * scrubbing mode, the frame it had read furthest ahead and not the one asked for. The mode stops a playing player where
 * it is, so one that is playing still draws it as much as one that was paused. Such a player is left out of the mode
 * until [enter] is called behind the first seek it is handed, which resets what it had read, and plays on until then.
 *
 * @param handler Runs the change on the player's thread.
 * @param holdsOff Whether the player standing now has played since its last seek, playing still or paused. Read on the
 * player's thread.
 * @param onEnded Called on the player's thread once neither a scrub nor a shuttle is under way.
 * @param setEnabled Puts the player standing now in the mode or takes it out, on the player's thread.
 */
internal class Media3ScrubMode(
  private val handler: Handler,
  private val holdsOff: () -> Boolean = { false },
  private val onEnded: () -> Unit = {},
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

  /**
   * Whether the player standing now was put in the mode. Read and written on the player's thread only.
   */
  var entered: Boolean = false
    private set

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
    entered = false
    enter()
  }

  /**
   * Puts the player in the mode when a scrub or a shuttle is under way and it was held out of it. Called on the
   * player's thread, behind a seek handed to the player.
   */
  fun enter() {
    if (active && !entered) set(true)
  }

  private fun apply() {
    val enabled = active
    handler.post {
      if (!enabled || !holdsOff()) set(enabled)
      if (!enabled) onEnded()
    }
  }

  private fun set(enabled: Boolean) {
    entered = enabled
    setEnabled(enabled)
  }
}
