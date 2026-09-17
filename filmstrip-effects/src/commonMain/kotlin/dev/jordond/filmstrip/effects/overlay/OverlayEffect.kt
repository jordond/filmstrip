package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * An effect drawn over the frame once geometry has settled.
 *
 * Runs in [EffectStage.Composite], so a position it names is a fraction of the frame the geometry
 * stage produced and lands in the same place at any preview or export resolution.
 *
 * @property visibleDuring When the overlay is drawn, or null for the whole composition.
 */
public interface OverlayEffect : EffectSpec {
  public val visibleDuring: TimeRange?

  /**
   * How the overlay is drawn at each frame of its run, or null to draw it the same way throughout.
   *
   * A callback has no serialized form, so a persisted edit list comes back with this null and the
   * overlay draws as it was authored. It is compared, so a caller holds one instance and reuses it
   * rather than rebuilding it: the built-in helpers compare by value and survive a rebuild, while a
   * lambda compares by identity and a rebuilt composition misses its thumbnail cache.
   */
  public val animation: OverlayAnimation? get() = null

  override val stage: EffectStage get() = EffectStage.Composite
}
