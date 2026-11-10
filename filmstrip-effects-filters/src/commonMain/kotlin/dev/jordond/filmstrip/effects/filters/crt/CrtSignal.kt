package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.straightRead
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Strengths at 1, and the patterns that stay fixed at every strength.
private const val SCANLINE_DEPTH = 0.35
private const val MASK_DEPTH = 0.15
private const val BARREL_K = 0.08
private const val VIGNETTE = 0.2
private const val GAIN_LIFT = 0.25
internal const val CRT_LINES = 480
internal const val CRT_TRIADS = 720

internal const val TWO_PI = 2.0 * PI

// Each channel's stripes sit a third of a triad along from the last.
internal const val MASK_PHASE_STEP = TWO_PI / 3.0

// Under this the mean of a cosine over a footprint is 1 to well past double precision.
private const val SINC_FLOOR = 1e-9

private const val FULL_SCALE = 1.0
private const val ALPHA = 3
private const val CENTRE = 0.5

// What the curve's slope gains per unit of squared position from the centre, the doubling in its distance squared and
// then differentiated.
internal const val CURVE_SLOPE = 8.0

/**
 * Every number the CRT look is made of, computed once here and consumed by all four lowerings.
 *
 * It all runs on SDR encoded codes, with positions as fractions of the frame and `v` counted from the top. The
 * scanlines and the mask are the mean of their pattern over a pixel's footprint, which is one pixel of the frame before
 * the curve. So a frame at any size draws the average of a larger one rather than an alias of the pattern.
 */
@InternalFilmstripApi
public object CrtSignal {
  /**
   * How many scanlines cover a frame's height at any size.
   */
  public const val LINES: Int = CRT_LINES

  /**
   * How many mask triads cover a frame's width at any size.
   */
  public const val TRIADS: Int = CRT_TRIADS

  /**
   * The mean of a cosine of [frequency] cycles a frame over a [footprint] that is a fraction of the frame, as a share
   * of the cosine's value at the footprint's centre.
   *
   * It goes negative past one cycle a footprint, where a pixel holds more of the cosine's far half than its near half.
   */
  public fun footprintAverage(
    frequency: Int,
    footprint: Double,
  ): Double {
    val angle = PI * frequency * footprint
    return if (abs(angle) < SINC_FLOOR) 1.0 else sin(angle) / angle
  }

  /**
   * What a scanline multiplies a pixel by at [y], a fraction of the height from the top, over a [footprint] of the
   * height, for a [depth] from `0.0` that leaves it alone.
   */
  public fun scanlineAt(
    y: Double,
    footprint: Double,
    depth: Double,
  ): Double = stripe(TWO_PI * CRT_LINES * y, footprintAverage(CRT_LINES, footprint), depth)

  /**
   * What the mask multiplies [channel] of a pixel by at [x], a fraction of the width, over a [footprint] of the width,
   * for a [depth] from `0.0` that leaves it alone.
   *
   * @param channel `0` for red, `1` for green and `2` for blue.
   */
  public fun maskAt(
    x: Double,
    channel: Int,
    footprint: Double,
    depth: Double,
  ): Double = stripe(TWO_PI * CRT_TRIADS * x - maskPhase(channel), footprintAverage(CRT_TRIADS, footprint), depth)

  /**
   * How far along a triad's cycle [channel]'s stripe sits, in radians.
   */
  public fun maskPhase(channel: Int): Double = channel * MASK_PHASE_STEP

  /**
   * How much of a unit half width a frame's sides span, as width and height, with the longer side at `1.0`.
   *
   * The curve and the vignette measure distance in these, so they read as circles on any frame shape.
   */
  public fun tubeFit(
    width: Int,
    height: Int,
  ): DoubleArray {
    val longer = max(width, height).toDouble()
    return doubleArrayOf(width / longer, height / longer)
  }

  /**
   * The squared distance of [u], [v] from the centre, in [tubeFit]'s units.
   */
  public fun tubeRadiusSquared(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
  ): Double {
    val fit = tubeFit(width, height)
    val across = 2.0 * (u - CENTRE) * fit[0]
    val down = 2.0 * (v - CENTRE) * fit[1]
    return across * across + down * down
  }

  /**
   * Where a pixel at [u], [v] of a frame [width] by [height] reads from, for a curve of [k].
   *
   * The point moves out from the centre by [k] times its squared distance from there, which fixes the centre and bows
   * the picture in from its edges.
   *
   * @return The source `u` and `v`, past `0.0` to `1.0` near the edge of the frame.
   */
  public fun barrelMap(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
    k: Double,
  ): DoubleArray {
    val spread = 1.0 + k * tubeRadiusSquared(u, v, width, height)
    return doubleArrayOf(CENTRE + (u - CENTRE) * spread, CENTRE + (v - CENTRE) * spread)
  }

  /**
   * The furthest the curve moves a read from its pixel, as a fraction of the side it runs along, which is a corner's.
   */
  public fun curveReach(
    width: Int,
    height: Int,
    k: Double,
  ): Double {
    val fit = tubeFit(width, height)
    return CENTRE * k * (fit[0] * fit[0] + fit[1] * fit[1])
  }

  /**
   * The share of an output pixel at [u], [v] whose picture lands inside the frame, for a curve of [k].
   *
   * A pixel's footprint reaches further in the source the further the curve stretches it, so each side's share comes
   * from how far the source sits inside that side, over how far one pixel reaches across it. It is `1.0` over the
   * picture, `0.0` past the curve and between them on a pixel the curve's edge crosses, so the edge reads as the mean
   * of that pixel at any size.
   */
  public fun coverageAt(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
    k: Double,
  ): Double {
    val source = barrelMap(u, v, width, height, k)
    val fit = tubeFit(width, height)
    val across = u - CENTRE
    val down = v - CENTRE
    val spread = 1.0 + k * tubeRadiusSquared(u, v, width, height)
    val turn = abs(CURVE_SLOPE * k * across * down)
    val reachAcross =
      (spread + CURVE_SLOPE * k * across * across * fit[0] * fit[0]) / width + turn * fit[1] * fit[1] / height
    val reachDown =
      (spread + CURVE_SLOPE * k * down * down * fit[1] * fit[1]) / height + turn * fit[0] * fit[0] / width
    return sideShare(source[0], reachAcross) * sideShare(source[1], reachDown)
  }

  /**
   * What the falloff multiplies a pixel by at [u], [v], from 1 at the centre to 1 less [strength] in a corner, linear
   * in squared distance.
   */
  public fun vignetteAt(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
    strength: Double,
  ): Double {
    val fit = tubeFit(width, height)
    return 1.0 - strength * tubeRadiusSquared(u, v, width, height) / (fit[0] * fit[0] + fit[1] * fit[1])
  }

  /**
   * The R'G'B' a pixel at [u], [v] of a frame [width] by [height] ends on, a pure function a test holds a backend's
   * pixels against.
   *
   * @param texel The picture with no look on it, the straight R'G'B' of a texel by its column and its row counted
   * along `v`, with alpha after it when the frame has one. The curve reads between texels, mixing the four around the
   * source position premultiplied and taking the mix back straight, so a clear texel lends its neighbours no colour.
   * That position runs past `0.0` to `1.0` near the curve's edge, where a read holds the edge texel.
   * @return red, green and blue, with the read's alpha after them when [texel] gives one.
   */
  public fun pixelAt(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
    params: CrtParams,
    texel: (column: Int, row: Int) -> DoubleArray,
  ): DoubleArray {
    val source = barrelMap(u, v, width, height, params.barrelK)
    val coverage = coverageAt(u, v, width, height, params.barrelK)
    val codes = straightRead(source[0], source[1], width, height, texel)
    return DoubleArray(codes.size) { channel ->
      if (channel == ALPHA) {
        codes[channel]
      } else {
        // An encoded code stops at full scale, so the gain's lift ends there too.
        min(FULL_SCALE, codes[channel] * multiplierAt(u, v, width, height, params, channel)) * coverage
      }
    }
  }

  /**
   * What [channel] of a pixel at [u], [v] is multiplied by ahead of coverage: the scanlines and the mask where the
   * pixel reads from, the vignette where it sits, and the gain.
   */
  internal fun multiplierAt(
    u: Double,
    v: Double,
    width: Int,
    height: Int,
    params: CrtParams,
    channel: Int,
  ): Double {
    val source = barrelMap(u, v, width, height, params.barrelK)
    return scanlineAt(source[1], 1.0 / height, params.scanlineDepth) *
      vignetteAt(u, v, width, height, params.vignette) * params.gain *
      maskAt(source[0], channel, 1.0 / width, params.maskDepth)
  }

  // The overlap of a footprint with the frame along one side. A frame is always wider than one footprint, so only the
  // nearer side can cut it.
  private fun sideShare(
    source: Double,
    reach: Double,
  ): Double = (min(source, 1.0 - source) / reach + CENTRE).coerceIn(0.0, 1.0)

  private fun stripe(
    phase: Double,
    average: Double,
    depth: Double,
  ): Double = 1.0 - depth * (0.5 - 0.5 * cos(phase) * average)
}

/**
 * The strengths of one [Crt].
 *
 * Built by [CrtParams.at], and read by every lowering so they all draw the same frame. The line and triad counts stay
 * fixed, so only these change with the control.
 *
 * @property scanlineDepth How far a scanline's dark falls, as a share of the code.
 * @property maskDepth How far the mask's dark falls, as a share of the code.
 * @property barrelK How strongly the picture curves, as [CrtSignal.barrelMap] reads it.
 * @property vignette How far a corner falls, as a share of the code.
 * @property gain What the frame is multiplied by to win back the light the patterns take.
 */
@InternalFilmstripApi
public class CrtParams private constructor(
  public val scanlineDepth: Double,
  public val maskDepth: Double,
  public val barrelK: Double,
  public val vignette: Double,
  public val gain: Double,
) {
  /**
   * Builds the values a [Crt] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Crt] at [intensity] draws with, each linear in it and with no change at all at `0.0`.
     */
    public fun at(intensity: Double): CrtParams =
      CrtParams(
        scanlineDepth = SCANLINE_DEPTH * intensity,
        maskDepth = MASK_DEPTH * intensity,
        barrelK = BARREL_K * intensity,
        vignette = VIGNETTE * intensity,
        gain = 1.0 + GAIN_LIFT * intensity,
      )

    /**
     * The values [crt] draws with.
     */
    public fun of(crt: Crt): CrtParams = at(crt.intensity.toDouble())
  }
}
