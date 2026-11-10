package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderBuilder
import kotlin.time.Duration

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
 * A [VhsBuilder.camcorder] inside [block] is added ahead of the [Vhs], so its display is recorded on the tape.
 */
public fun EffectsBuilder.vhs(block: VhsBuilder.() -> Unit = {}): EffectsBuilder {
  val builder = VhsBuilder().apply(block)
  builder.camcorder?.let { add(it) }
  return add(builder.build())
}

/**
 * Describes a [Vhs]. Every value starts at the one [Vhs] uses unless it is given one.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property tape The tape speed, which sets how soft the picture is.
 * @property seed Picks the pattern of noise and events. Equal seeds draw the same tape.
 * @property tracking The band of mistracked tape, built with [events] or [rolling], or [Tracking.Off].
 */
@FilmstripDsl
public class VhsBuilder internal constructor() {
  public var intensity: Float = 1f
  public var tape: TapeSpeed = TapeSpeed.EP
  public var seed: Int = Vhs.DEFAULT_SEED
  public var tracking: Tracking = Tracking.Events()
  internal var camcorder: Camcorder? = null
    private set

  /**
   * Records a [Camcorder] display described by [block] on the tape, so the look degrades it with the picture.
   *
   * @throws IllegalArgumentException when this [vhs] already records a camcorder.
   */
  public fun camcorder(block: CamcorderBuilder.() -> Unit) {
    require(camcorder == null) { "A vhs records one camcorder, and this vhs already has one." }
    camcorder = CamcorderBuilder().apply(block).build()
  }

  /**
   * Bands that turn up now and then with [chance] in each four second window, shaped by [block].
   */
  public fun events(
    chance: Float = Tracking.DEFAULT_CHANCE,
    block: TrackingBuilder.() -> Unit = {},
  ): Tracking.Events {
    val band = TrackingBuilder().apply(block)
    return Tracking.Events(chance, band.height, band.shift, band.snow)
  }

  /**
   * One band rolling top to bottom every [period], shaped by [block].
   */
  public fun rolling(
    period: Duration = Tracking.DEFAULT_PERIOD,
    block: TrackingBuilder.() -> Unit = {},
  ): Tracking.Rolling {
    val band = TrackingBuilder().apply(block)
    return Tracking.Rolling(period, band.height, band.shift, band.snow)
  }

  internal fun build(): Vhs = Vhs(intensity, tape, seed, tracking)
}

/**
 * Shapes a [Tracking] band for [VhsBuilder.events] or [VhsBuilder.rolling].
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
