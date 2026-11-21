package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.OsdCorner
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.camcorder.OsdParams
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt

/**
 * Adds a [Digicam] look described by [block].
 *
 * ```
 * digicam {
 *   sharpness = 0.8f
 *   noise = 0.4f
 *   stamp(RecordedAt(2003, 8, 21))
 * }
 * ```
 *
 * A [DigicamBuilder.stamp] inside [block] is added after the [Digicam], so the date sits clean on top of the noise.
 */
public fun EffectsBuilder.digicam(block: DigicamBuilder.() -> Unit = {}): EffectsBuilder {
  val builder = DigicamBuilder().apply(block)
  add(builder.build())
  builder.stamp?.let { add(it) }
  return this
}

/**
 * Describes a [Digicam]. Every value starts at the one [Digicam] uses unless it is given one.
 *
 * @property sharpness How strong the halo around edges is, from `0f` to `1f`.
 * @property noise How strong the coloured noise in the shadows is, from `0f` to `1f`.
 * @property clip How far highlights are pushed into clipping, from `0f` to `1f`.
 * @property seed Picks the noise pattern.
 */
@FilmstripDsl
public class DigicamBuilder internal constructor() {
  public var sharpness: Float = DEFAULT.sharpness
  public var noise: Float = DEFAULT.noise
  public var clip: Float = DEFAULT.clip
  public var seed: Int = DEFAULT.seed
  internal var stamp: Camcorder? = null
    private set

  /**
   * Adds the camera's orange date stamp in [corner], reading [recordedAt] plus the composition time.
   *
   * It shows as `'97 6 14`, drawn by a [Camcorder] holding one [OsdElement.Stamp].
   */
  public fun stamp(
    recordedAt: RecordedAt = Camcorder.DEFAULT_RECORDED_AT,
    corner: OsdCorner = OsdCorner.BottomRight,
  ) {
    stamp = Camcorder(listOf(OsdElement.Stamp(corner)), recordedAt, OsdParams.STAMP_TEXT_SIZE)
  }

  internal fun build(): Digicam = Digicam(sharpness, noise, clip, seed)

  private companion object {
    val DEFAULT = Digicam()
  }
}
