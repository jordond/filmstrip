package dev.jordond.filmstrip.effect

import kotlin.time.Duration

/**
 * The clock a preview runs on, for an effect that moves with real time instead of with the playhead.
 *
 * It starts at zero when the preview's player is built and never goes back. It keeps running while the preview is
 * paused, which is what lets an effect that says [EffectSpec.redrawsWhileHeld] draw a different picture over a frame
 * that stands still. An export has no such clock, see [Attributes.previewClock].
 */
public fun interface PreviewClock {
  /**
   * How long the preview's player has existed.
   */
  public fun now(): Duration
}
