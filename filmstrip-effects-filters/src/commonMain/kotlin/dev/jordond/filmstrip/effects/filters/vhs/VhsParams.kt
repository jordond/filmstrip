package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.max
import kotlin.math.sqrt

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
 * @property trackingChance The chance a tracking band runs in a window.
 * @property trackingShift The largest shift inside a tracking band, a fraction of the width.
 * @property trackingNoise The largest streak inside a tracking band, in code values.
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
  public val trackingChance: Double,
  public val trackingShift: Double,
  public val trackingNoise: Double,
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
     * The values a [Vhs] at [intensity] on [tape] draws with.
     *
     * Strengths scale linearly, blur sigma included, so intensity 0 is the source. Event chances scale by intensity
     * squared. Rates, cycles, and the shapes of streaks and bands stay fixed.
     */
    public fun at(
      intensity: Double,
      tape: TapeSpeed,
    ): VhsParams {
      val eventScale = intensity * intensity
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
        trackingChance = TRACK_CHANCE * eventScale,
        trackingShift = TRACK_SHIFT * intensity,
        trackingNoise = TRACK_NOISE * intensity,
        desaturation = DESATURATION * intensity,
        lift = LIFT * intensity,
      )
    }

    /**
     * The values [vhs] draws with.
     */
    public fun of(vhs: Vhs): VhsParams = at(vhs.intensity.toDouble(), vhs.tape)
  }
}

// Strengths at intensity 1, in fractions of the width or code values.
private const val HALO_GAIN = 0.3
private const val JITTER = 0.0006
private const val WAVE = 0.0015
private const val HEAD_SHIFT = 0.1042
private const val CHROMA_SHIFT = 0.004
private const val LUMA_NOISE = 0.008
private const val DROPOUT_CHANCE = 0.001
private const val TRACK_CHANCE = 0.2
private const val TRACK_SHIFT = 0.02
private const val TRACK_NOISE = 0.12
private const val DESATURATION = 0.12
private const val LIFT = 0.03
