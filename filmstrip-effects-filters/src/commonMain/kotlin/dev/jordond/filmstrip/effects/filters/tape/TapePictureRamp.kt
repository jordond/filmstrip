package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.motion.Easing
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.DurationUnit

/**
 * The strength a [TapeLook] has on each tick while it settles, which every renderer reads.
 *
 * The level holds [from] up to [startTick], eases to [to] over [overTicks], and holds [to] from [endTick] on. A tick
 * is measured from [startTick] as a whole number of ticks, so the level is a function of the tick alone. The progress
 * is paced by the [easing] and the level is `from * (1 - progress) + to * progress`, which is exactly [from] at the
 * start and exactly [to] at the end.
 *
 * @property from The level before and on [startTick].
 * @property to The level from [endTick] on, the look's own intensity.
 * @property startTick The tick of the first frame of the scope the look settles over.
 * @property overTicks How many ticks the ease takes.
 * @property easing How the ease is paced.
 */
@InternalFilmstripApi
public class TapePictureRamp private constructor(
  public val from: Double,
  public val to: Double,
  public val startTick: Int,
  public val overTicks: Double,
  public val easing: Easing,
) {
  /**
   * The first tick whose level is [to], which is [startTick] plus [overTicks] rounded up.
   */
  public val endTick: Int = ceil(startTick + overTicks).toInt()

  /**
   * The larger of [from] and [to], the level a graph sized for the look has to hold.
   */
  public val peak: Double get() = max(from, to)

  /**
   * The strength the level is measured against for the colour a tracking band takes, which is [to] unless the look
   * settles to nothing, and then [from].
   */
  public val colourReference: Double get() = colourReferenceOf(to, from)

  /**
   * The level at [tick].
   */
  public fun levelAt(tick: Int): Double {
    val progress = ((tick.toLong() - startTick) / overTicks).coerceIn(0.0, 1.0)
    val eased = easing.paced(progress.toFloat()).toDouble()
    return from * (1.0 - eased) + to * eased
  }

  @InternalFilmstripApi
  public companion object {
    /**
     * The share of its colour loss a tracking band keeps at [level], for a look of strength [intensity] that settles
     * from [from], or that does not settle when [from] is null.
     *
     * A band's other figures follow the level, so its colour loss does too: it is `level / intensity` up to the
     * strength the look settles to and 1 from there, and `level / from` for a look that settles to nothing. A look that
     * does not settle keeps the whole loss.
     */
    public fun colourShare(
      level: Double,
      intensity: Double,
      from: Double?,
    ): Double {
      if (from == null) return 1.0
      val reference = colourReferenceOf(intensity, from)
      return if (reference > 0.0) min(1.0, level / reference) else 1.0
    }

    private fun colourReferenceOf(
      to: Double,
      from: Double,
    ): Double = if (to > 0.0) to else from

    /**
     * The ramp [look] settles along over [span], or null when it has none or settles to where it starts.
     *
     * The ramp starts on the tick of the first frame of [span], the clip's slot for a clip and the composition for a
     * composition.
     */
    public fun of(
      look: TapeLook,
      span: TimeRange,
    ): TapePictureRamp? {
      val settle = look.settle ?: return null
      if (settle.from == look.intensity) return null
      return TapePictureRamp(
        from = settle.from.toDouble(),
        to = look.intensity.toDouble(),
        startTick = TapePictureSignal.tickAt(span.start.toDouble(DurationUnit.SECONDS)),
        overTicks = settle.over.toDouble(DurationUnit.SECONDS) * TICK_RATE,
        easing = settle.easing,
      )
    }
  }
}
