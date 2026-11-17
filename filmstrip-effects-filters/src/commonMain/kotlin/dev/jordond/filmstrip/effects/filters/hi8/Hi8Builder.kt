package dev.jordond.filmstrip.effects.filters.hi8

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.tape.addTapeLook

/**
 * Adds a [Hi8] look described by [block].
 *
 * ```
 * hi8 {
 *   intensity = 0.6f
 *   tape = Hi8Speed.LP
 *   camcorder { rec() }
 * }
 * ```
 *
 * A [TapeLookBuilder.camcorder] inside [block] is added ahead of the [Hi8], so its display is recorded on the tape.
 */
public fun EffectsBuilder.hi8(block: Hi8Builder.() -> Unit = {}): EffectsBuilder =
  addTapeLook(Hi8Builder().apply(block))

/**
 * Describes a [Hi8]. Every value starts at the one [Hi8] uses unless it is given one.
 *
 * @property tape The tape speed, which sets how soft the picture is.
 */
@FilmstripDsl
public class Hi8Builder internal constructor() : TapeLookBuilder("hi8") {
  public var tape: Hi8Speed = Hi8Speed.SP

  override fun build(): Hi8 = Hi8(intensity, tape, seed, tracking, clock, settle)
}
