package dev.jordond.filmstrip

/**
 * A handle that stops the work it was returned from.
 *
 * Every callback-based extension point returns one. Implementations must tolerate [cancel] being
 * called more than once, and after the work has already finished.
 */
public fun interface Cancellable {
  /**
   * Stops the work, if it has not already stopped. Idempotent.
   */
  public fun cancel()
}
