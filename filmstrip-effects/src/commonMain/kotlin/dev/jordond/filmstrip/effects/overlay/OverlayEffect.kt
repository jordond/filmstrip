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

  override val stage: EffectStage get() = EffectStage.Composite
}
