package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingSettle
import dev.jordond.filmstrip.motion.Easing
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The strengths of one [TapeLook], in fractions of the frame width or code values.
 *
 * Built by [TapePictureParams.at], and read by every lowering so they all draw the same tape.
 *
 * @property lumaSigma The luma blur, a fraction of the width.
 * @property chromaSigma The chroma blur, a fraction of the width.
 * @property haloGain How far the edge ring pushes luma away from its wide blur.
 * @property jitter The largest per-row shift, a fraction of the width.
 * @property wave The size of the slow edge wave, a fraction of the width.
 * @property headShift The largest head switching shift, a fraction of the width.
 * @property chromaShift How far chroma lands to the right of luma, a fraction of the width.
 * @property lumaNoise The largest luma streak, in code values.
 * @property chromaNoise The largest chroma streak, in code values.
 * @property dropoutChance The chance a row holds a dropout on a tick.
 * @property dropoutLength How long a dropout of middling length runs, a fraction of the width.
 * @property trackingRolls Whether the tracking band rolls through the frame on a period rather than turning up now and
 * then.
 * @property trackingChance The chance a tracking band runs in a window, 0 when it rolls.
 * @property trackingPeriodTicks How many ticks a rolling band takes to cross the frame.
 * @property trackingAnchorTicks The tick a rolling band is at the top of the frame on, 0 for a look that has no
 * composition time to anchor to.
 * @property trackingSettleTicks How many ticks a rolling band takes to ease off from its anchor, 0 when it stays at
 * full strength.
 * @property trackingSettleEasing How the band's ease off is paced.
 * @property trackingChanceFrom The chance of a tracking band at the strength a settle starts from, which is
 * [trackingChance] for a look that does not settle.
 * @property trackingChanceTo The chance of a tracking band at the strength a settle ends on, which is [trackingChance]
 * for a look that does not settle. An event band whose roll is under the smaller of the two runs through the settle,
 * and one between them fades in or out along it, so none steps on or off and the settled look is the plain one.
 * @property trackingHeight How tall a tracking band is, a fraction of the height.
 * @property trackingShift The largest shift inside a tracking band, a fraction of the width.
 * @property trackingNoise The largest streak inside a tracking band, in code values.
 * @property trackingSnow How far the middle of a tracking band mixes toward snow, from 0 to 1.
 * @property trackingChromaLoss How much of the chroma the middle of a tracking band takes, from 0 to 0.5, which a look
 * with no band never reads. A look that settles scales it with its level, so the colour goes and returns with the rest.
 * @property desaturation How much chroma is lost, from 0 to 1.
 * @property lift How far black is lifted, in code values.
 */
@InternalFilmstripApi
public class TapePictureParams private constructor(
  public val lumaSigma: Double,
  public val chromaSigma: Double,
  public val haloGain: Double,
  public val jitter: Double,
  public val wave: Double,
  public val headShift: Double,
  public val chromaShift: Double,
  public val lumaNoise: Double,
  public val chromaNoise: Double,
  public val dropoutChance: Double,
  public val dropoutLength: Double,
  public val trackingRolls: Boolean,
  public val trackingChance: Double,
  public val trackingPeriodTicks: Double,
  public val trackingAnchorTicks: Int,
  public val trackingSettleTicks: Double,
  public val trackingSettleEasing: Easing,
  public val trackingChanceFrom: Double,
  public val trackingChanceTo: Double,
  public val trackingHeight: Double,
  public val trackingShift: Double,
  public val trackingNoise: Double,
  public val trackingSnow: Double,
  public val trackingChromaLoss: Double,
  public val desaturation: Double,
  public val lift: Double,
) {
  /**
   * The sigma the edge ring is measured against, a fraction of the width. It is the luma blur followed by the halo's
   * own, which for two Gaussians adds in squares.
   */
  public val wideSigma: Double
    get() = sqrt(lumaSigma * lumaSigma + HALO_SIGMA * HALO_SIGMA)

  /**
   * How far the smooth edge of a tracking band reaches either side of its centre, half of [trackingHeight] as a
   * fraction of the height.
   */
  public val trackingHalfHeight: Double
    get() = 0.5 * trackingHeight

  /**
   * How far a pixel can read from its own column, a fraction of the width.
   */
  public val widestReach: Double
    get() {
      val blur = max(wideSigma * LUMA_TAP_STEP * LUMA_TAPS, chromaSigma * CHROMA_TAP_STEP * CHROMA_TAPS)
      return blur + chromaShift + jitter + wave + headShift * (1.0 + HEAD_WOBBLE) + trackingShift
    }

  /**
   * Builds the values a [TapeLook] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    // The values a look at [level] on [stock] with [tracking] draws with. Strengths scale linearly, blur sigma
    // included, so level 0 is the source and runs no tracking band. Event chances scale by level squared. Rates,
    // cycles, and the shapes of streaks, bands and dropouts stay fixed. A rolling band's anchor counts from
    // [scopeStart], the first frame of the scope the look is in, so it is the same band in whichever slot the scope
    // sits. A look that has no [scopeStart], as one drawn from a preview's own clock has none, draws a band from tick
    // 0 and draws a band that settles as it is once it has, gone. [colourShare] is the share of its colour loss the
    // band keeps. [settleFrom] and [settleTo] are the strengths a settle runs between, which an event band's fade
    // reads.
    internal fun at(
      level: Double,
      stock: PictureStock,
      tracking: Tracking = Tracking.Events(),
      scopeStart: Duration? = Duration.ZERO,
      colourShare: Double = 1.0,
      settleFrom: Double = level,
      settleTo: Double = level,
    ): TapePictureParams {
      val eventScale = level * level
      val settledAway = scopeStart == null && tracking is Tracking.Rolling && tracking.settle != null
      val band = if (level > 0.0 && !settledAway) tracking else Tracking.Off
      val timed = scopeStart != null
      val start = scopeStart ?: Duration.ZERO
      val shape =
        when (band) {
          is Tracking.Events -> BandShape(band.chance, Tracking.DEFAULT_PERIOD, band.height, band.shift, band.snow)
          is Tracking.Rolling -> BandShape(0f, band.period, band.height, band.shift, band.snow, band.at, band.settle)
          Tracking.Off -> BandShape(0f, Tracking.DEFAULT_PERIOD, Tracking.DEFAULT_HEIGHT, 0f, 0f)
        }
      return TapePictureParams(
        lumaSigma = TapePictureSignal.sigmaFor(stock.lumaMHz) * level,
        chromaSigma = TapePictureSignal.sigmaFor(stock.chromaMHz) * level,
        haloGain = HALO_GAIN * level,
        jitter = JITTER * level,
        wave = WAVE * level,
        headShift = HEAD_SHIFT * level,
        chromaShift = CHROMA_SHIFT * stock.chromaShift * level,
        lumaNoise = LUMA_NOISE * stock.lumaNoise * level,
        chromaNoise = LUMA_NOISE * CHROMA_NOISE_RATIO * stock.chromaNoise * level,
        dropoutChance = DROPOUT_CHANCE * stock.dropoutChance * eventScale,
        dropoutLength = stock.dropoutLength,
        trackingRolls = band is Tracking.Rolling,
        trackingChance = shape.chance.decimal() * eventScale,
        trackingPeriodTicks = shape.period.toDouble(DurationUnit.SECONDS) * TICK_RATE,
        trackingAnchorTicks =
          if (timed) {
            TapePictureSignal.tickAt(
              (start + shape.at).toDouble(DurationUnit.SECONDS),
            )
          } else {
            0
          },
        trackingSettleTicks =
          shape.settle
            .takeIf { timed }
            ?.over
            ?.toDouble(DurationUnit.SECONDS)
            ?.times(TICK_RATE) ?: 0.0,
        trackingSettleEasing = shape.settle?.easing ?: Easing.Linear,
        trackingChanceFrom = shape.chance.decimal() * (settleFrom * settleFrom),
        trackingChanceTo = shape.chance.decimal() * (settleTo * settleTo),
        trackingHeight = shape.height.decimal(),
        trackingShift = shape.shift.decimal() * level,
        trackingNoise = if (band == Tracking.Off) 0.0 else TRACK_NOISE * level,
        trackingSnow = shape.snow.decimal() * level,
        trackingChromaLoss = TRACK_CHROMA_LOSS * colourShare,
        desaturation = DESATURATION * level,
        lift = LIFT * level,
      )
    }

    /**
     * The values [look] at [level] draws with in a scope whose first frame is at [scopeStart].
     *
     * A rolling band is at the top of the frame [Tracking.Rolling.at] after [scopeStart], and its colour loss follows
     * [level] as [TapePictureRamp.colourShare] gives.
     */
    internal fun at(
      level: Double,
      stock: PictureStock,
      look: TapeLook,
      scopeStart: Duration?,
    ): TapePictureParams =
      at(
        level,
        stock,
        look.tracking,
        scopeStart,
        TapePictureRamp.colourShare(level, look.intensity.toDouble(), look.settle?.from?.toDouble()),
        look.settle?.from?.toDouble() ?: level,
        look.settle?.let { look.intensity.toDouble() } ?: level,
      )

    /**
     * The values [look] draws with once it has settled, which is all it draws with when it does not settle, in a scope
     * whose first frame is at [scopeStart].
     */
    public fun of(
      look: TapeLook,
      scopeStart: Duration = Duration.ZERO,
    ): TapePictureParams = look.pictureParams(look.intensity.toDouble(), scopeStart)
  }
}

private class BandShape(
  val chance: Float,
  val period: Duration,
  val height: Float,
  val shift: Float,
  val snow: Float,
  val at: Duration = Duration.ZERO,
  val settle: TrackingSettle? = null,
)

// A tracking value as the decimal it was written as, so 0.2f reads as 0.2 on every platform.
private fun Float.decimal(): Double = toString().toDouble()

// The share of the chance's travel over a settle an event band takes to fade in or out, a quarter of it, ending no
// later than the chance's end. A band that arrives last is whole when the settle ends, so a settled look is the plain
// one. Smaller steps more on each tick: an ease in steps up to 16 / N of full strength, N the ticks of the settle.
internal const val EVENT_FADE = 0.25

// Strengths of a VHS tape at intensity 1, in fractions of the width or code values.
private const val HALO_GAIN = 0.3
private const val JITTER = 0.0002
private const val WAVE = 0.0015
private const val HEAD_SHIFT = 0.1042
private const val CHROMA_SHIFT = 0.004
private const val LUMA_NOISE = 0.008
private const val DROPOUT_CHANCE = 0.0002
private const val TRACK_NOISE = 0.12
private const val DESATURATION = 0.25
private const val LIFT = 0.03
