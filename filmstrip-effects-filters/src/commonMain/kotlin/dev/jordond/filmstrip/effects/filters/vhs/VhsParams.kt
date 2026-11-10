package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The strengths of one [Vhs], in fractions of the frame width or code values.
 *
 * Built by [VhsParams.at], and read by every lowering so they all draw the same tape.
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
 * @property trackingRolls Whether the tracking band rolls through the frame on a period rather than turning up now and
 * then.
 * @property trackingChance The chance a tracking band runs in a window, 0 when it rolls.
 * @property trackingPeriodTicks How many ticks a rolling band takes to cross the frame.
 * @property trackingHeight How tall a tracking band is, a fraction of the height.
 * @property trackingShift The largest shift inside a tracking band, a fraction of the width.
 * @property trackingNoise The largest streak inside a tracking band, in code values.
 * @property trackingSnow How far the middle of a tracking band mixes toward snow, from 0 to 1.
 * @property desaturation How much chroma is lost, from 0 to 1.
 * @property lift How far black is lifted, in code values.
 */
@InternalFilmstripApi
public class VhsParams private constructor(
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
  public val trackingRolls: Boolean,
  public val trackingChance: Double,
  public val trackingPeriodTicks: Double,
  public val trackingHeight: Double,
  public val trackingShift: Double,
  public val trackingNoise: Double,
  public val trackingSnow: Double,
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
   * Builds the values a [Vhs] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Vhs] at [intensity] on [tape] with [tracking] draws with.
     *
     * Strengths scale linearly, blur sigma included, so intensity 0 is the source and runs no tracking band. Event
     * chances scale by intensity squared. Rates, cycles, and the shapes of streaks and bands stay fixed.
     */
    public fun at(
      intensity: Double,
      tape: TapeSpeed,
      tracking: Tracking = Tracking.Events(),
    ): VhsParams {
      val eventScale = intensity * intensity
      val band = if (intensity > 0.0) tracking else Tracking.Off
      val shape =
        when (band) {
          is Tracking.Events -> BandShape(band.chance, Tracking.DEFAULT_PERIOD, band.height, band.shift, band.snow)
          is Tracking.Rolling -> BandShape(0f, band.period, band.height, band.shift, band.snow)
          Tracking.Off -> BandShape(0f, Tracking.DEFAULT_PERIOD, Tracking.DEFAULT_HEIGHT, 0f, 0f)
        }
      return VhsParams(
        lumaSigma = VhsSignal.sigmaFor(tape.lumaMHz) * intensity,
        chromaSigma = VhsSignal.sigmaFor(tape.chromaMHz) * intensity,
        haloGain = HALO_GAIN * intensity,
        jitter = JITTER * intensity,
        wave = WAVE * intensity,
        headShift = HEAD_SHIFT * intensity,
        chromaShift = CHROMA_SHIFT * intensity,
        lumaNoise = LUMA_NOISE * intensity,
        chromaNoise = LUMA_NOISE * CHROMA_NOISE_RATIO * intensity,
        dropoutChance = DROPOUT_CHANCE * eventScale,
        trackingRolls = band is Tracking.Rolling,
        trackingChance = shape.chance.decimal() * eventScale,
        trackingPeriodTicks = shape.period.toDouble(DurationUnit.SECONDS) * TICK_RATE,
        trackingHeight = shape.height.decimal(),
        trackingShift = shape.shift.decimal() * intensity,
        trackingNoise = if (band == Tracking.Off) 0.0 else TRACK_NOISE * intensity,
        trackingSnow = shape.snow.decimal() * intensity,
        desaturation = DESATURATION * intensity,
        lift = LIFT * intensity,
      )
    }

    /**
     * The values [vhs] draws with.
     */
    public fun of(vhs: Vhs): VhsParams = at(vhs.intensity.toDouble(), vhs.tape, vhs.tracking)
  }
}

private class BandShape(
  val chance: Float,
  val period: Duration,
  val height: Float,
  val shift: Float,
  val snow: Float,
)

// A tracking value as the decimal it was written as, so 0.2f reads as 0.2 on every platform.
private fun Float.decimal(): Double = toString().toDouble()

// Strengths at intensity 1, in fractions of the width or code values.
private const val HALO_GAIN = 0.3
private const val JITTER = 0.0006
private const val WAVE = 0.0015
private const val HEAD_SHIFT = 0.1042
private const val CHROMA_SHIFT = 0.004
private const val LUMA_NOISE = 0.008
private const val DROPOUT_CHANCE = 0.001
private const val TRACK_NOISE = 0.12
private const val DESATURATION = 0.12
private const val LIFT = 0.03
