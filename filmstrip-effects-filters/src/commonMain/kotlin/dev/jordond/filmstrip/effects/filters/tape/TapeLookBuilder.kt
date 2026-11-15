package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderBuilder
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingBuilder
import kotlin.time.Duration

/**
 * Describes what every [TapeLook] has. Each value starts at the one the look uses unless it is given one.
 *
 * @property intensity How strong the look is, from `0f` which leaves the frame alone to `1f`.
 * @property seed Picks the pattern of noise and events. Equal seeds draw the same tape.
 * @property tracking The band of mistracked tape, built with [events] or [rolling], or [Tracking.Off].
 * @property clock What the pattern moves with in a preview. [TapeClock.Wall] keeps it moving while paused.
 */
@FilmstripDsl
public abstract class TapeLookBuilder internal constructor(
  private val format: String,
) {
  public var intensity: Float = 1f
  public var seed: Int = TapeLook.DEFAULT_SEED
  public var tracking: Tracking = Tracking.Events()
  public var clock: TapeClock = TapeClock.Composition
  internal var camcorder: Camcorder? = null
    private set

  /**
   * Records a [Camcorder] display described by [block] on the tape, so the look degrades it with the picture.
   *
   * @throws IllegalArgumentException when this look already records a camcorder.
   */
  public fun camcorder(block: CamcorderBuilder.() -> Unit) {
    require(camcorder == null) { "A $format records one camcorder, and this $format already has one." }
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

  internal abstract fun build(): TapeLook
}

// Adds the look a builder describes, with its camcorder ahead of it so the display is recorded on the tape.
internal fun EffectsBuilder.addTapeLook(builder: TapeLookBuilder): EffectsBuilder {
  builder.camcorder?.let { add(it) }
  return add(builder.build())
}
