package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * How a YCbCr picture's luma and two chroma samples turn back into red, green and blue under an ITU-T H.273 matrix
 * coefficients code point, as [ycbcrReading] reads one.
 *
 * [KrKb] decodes with the non-constant luminance equations, [ConstantLuminance] with the constant luminance ones,
 * [Identity] holds green, blue and red in the three samples as they are and [YCgCo] holds luma, green and orange
 * differences. [Refused] stands for a code point no backend reads. Turn samples into color with [ycbcrToRgb] or an
 * [evaluator], and find the matrix a decoder that cannot read this one lands closest with [nearestReadable].
 */
@InternalFilmstripApi
public sealed interface YcbcrReading {
  /**
   * The non-constant luminance equations with luma weights [kr] for red and [kb] for blue, green taking the rest.
   */
  @InternalFilmstripApi
  public data class KrKb(
    public val kr: Double,
    public val kb: Double,
  ) : YcbcrReading

  /**
   * The identity matrix, where the luma, blue difference and red difference samples hold green, blue and red, each
   * scaled as luma is.
   */
  @InternalFilmstripApi
  public data object Identity : YcbcrReading

  /**
   * YCgCo, where the blue difference sample holds the green difference and the red difference sample holds the
   * orange difference, both scaled as luma is.
   */
  @InternalFilmstripApi
  public data object YCgCo : YcbcrReading

  /**
   * The constant luminance equations, with luma weights [kr] and [kb] applied to linear light and the result encoded
   * through [oetf].
   *
   * Each difference sample takes one divisor below zero and another above it, twice the reach of that difference,
   * which [oetf] fixes for [kr] and [kb].
   */
  @InternalFilmstripApi
  public data class ConstantLuminance(
    public val kr: Double,
    public val kb: Double,
    public val oetf: CicpOetf,
  ) : YcbcrReading

  /**
   * A matrix no backend reads, with the [reason] a backend reports when it refuses the picture.
   */
  @InternalFilmstripApi
  public data class Refused(
    public val reason: String,
  ) : YcbcrReading
}
