package dev.jordond.filmstrip.effects.filters.betamax

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.tape.addTapeLook

/**
 * Adds a [Betamax] look described by [block].
 *
 * ```
 * betamax {
 *   intensity = 0.6f
 *   tape = BetaSpeed.I
 *   camcorder { rec() }
 * }
 * ```
 *
 * A [TapeLookBuilder.camcorder] inside [block] is added ahead of the [Betamax], so its display is recorded on the tape.
 */
public fun EffectsBuilder.betamax(block: BetamaxBuilder.() -> Unit = {}): EffectsBuilder =
  addTapeLook(BetamaxBuilder().apply(block))

/**
 * Describes a [Betamax]. Every value starts at the one [Betamax] uses unless it is given one.
 *
 * @property tape The tape speed, which sets how soft the picture is.
 */
@FilmstripDsl
public class BetamaxBuilder internal constructor() : TapeLookBuilder("betamax") {
  public var tape: BetaSpeed = BetaSpeed.II

  override fun build(): Betamax = Betamax(intensity, tape, seed, tracking, clock)
}
