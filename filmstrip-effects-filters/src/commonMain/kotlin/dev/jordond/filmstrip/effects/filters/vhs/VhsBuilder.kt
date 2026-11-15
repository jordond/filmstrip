package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.tape.addTapeLook

/**
 * Adds a [Vhs] look described by [block].
 *
 * ```
 * vhs {
 *   intensity = 0.6f
 *   tape = TapeSpeed.SP
 *   tracking = rolling(period = 6.seconds) { snow = 0.8f }
 *   camcorder { rec() }
 * }
 * ```
 *
 * A [TapeLookBuilder.camcorder] inside [block] is added ahead of the [Vhs], so its display is recorded on the tape.
 */
public fun EffectsBuilder.vhs(block: VhsBuilder.() -> Unit = {}): EffectsBuilder =
  addTapeLook(VhsBuilder().apply(block))

/**
 * Describes a [Vhs]. Every value starts at the one [Vhs] uses unless it is given one.
 *
 * @property tape The tape speed, which sets how soft the picture is.
 */
@FilmstripDsl
public class VhsBuilder internal constructor() : TapeLookBuilder("vhs") {
  public var tape: TapeSpeed = TapeSpeed.EP

  override fun build(): Vhs = Vhs(intensity, tape, seed, tracking, clock)
}

/**
 * Shapes a [Tracking] band for [TapeLookBuilder.events] or [TapeLookBuilder.rolling].
 *
 * @property height How tall the band is, as a fraction of the frame height from `0.02f` to `0.5f`.
 * @property shift The largest sideways slide inside the band, as a fraction of the frame width from `0f` to `0.1f`.
 * @property snow How far the middle of the band mixes toward snow, from `0f` which keeps the picture to `1f`.
 */
@FilmstripDsl
public class TrackingBuilder internal constructor() {
  public var height: Float = Tracking.DEFAULT_HEIGHT
  public var shift: Float = Tracking.DEFAULT_SHIFT
  public var snow: Float = Tracking.DEFAULT_SNOW
}
