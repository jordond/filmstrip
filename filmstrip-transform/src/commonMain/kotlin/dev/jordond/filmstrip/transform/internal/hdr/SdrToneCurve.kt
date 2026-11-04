package dev.jordond.filmstrip.transform.internal.hdr

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.sceneFromHlgSignal
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.pow

// The one HDR to SDR tone curve every backend runs, transcribed from media3 1.11.1's OpenGL tone map in
// fragment_shader_transformation_external_yuv_es3.glsl. The shader's literals are the definition, so a constant here
// is the shader's value and not a rounded or textbook one. A backend lowers these pieces and never re-derives them.

/**
 * Three linear or encoded channels, red, green and blue, in that order.
 */
@InternalFilmstripApi
public data class Rgb(
  val r: Double,
  val g: Double,
  val b: Double,
) {
  /**
   * Applies [transform] to each channel on its own.
   */
  public inline fun map(transform: (Double) -> Double): Rgb = Rgb(transform(r), transform(g), transform(b))

  /**
   * Scales every channel by one [gain].
   */
  public operator fun times(gain: Double): Rgb = Rgb(r * gain, g * gain, b * gain)

  /**
   * The weighted sum of the channels, with [weights] read channel for channel.
   */
  public infix fun dot(weights: Rgb): Double = r * weights.r + g * weights.g + b * weights.b
}

/**
 * A three by three matrix on [Rgb], stored as its three rows.
 */
@InternalFilmstripApi
public data class RgbMatrix(
  val red: Rgb,
  val green: Rgb,
  val blue: Rgb,
) {
  /**
   * The matrix applied to [rgb] as a column.
   */
  public operator fun times(rgb: Rgb): Rgb = Rgb(red dot rgb, green dot rgb, blue dot rgb)

  /**
   * The matrix product, which applies [other] first and then this one.
   */
  public operator fun times(other: RgbMatrix): RgbMatrix {
    val red = Rgb(other.red.r, other.green.r, other.blue.r)
    val green = Rgb(other.red.g, other.green.g, other.blue.g)
    val blue = Rgb(other.red.b, other.green.b, other.blue.b)

    fun row(row: Rgb) = Rgb(row dot red, row dot green, row dot blue)

    return RgbMatrix(row(this.red), row(this.green), row(this.blue))
  }
}

/**
 * The display light the tone curve's SDR output reaches at full signal, in cd/m2.
 *
 * Tone mapped light is divided by this before the OETF, so a PQ source at this many nits after the knee encodes as
 * the full SDR signal.
 */
@InternalFilmstripApi
public const val TONE_MAP_SDR_PEAK_NITS: Double = 500.0

/**
 * The brightest PQ light the knee maps, in cd/m2.
 *
 * PQ light above it is read as this level, so it lands on [TONE_MAP_SDR_PEAK_NITS].
 */
@InternalFilmstripApi
public const val TONE_MAP_MASTERING_PEAK_NITS: Double = 1000.0

/**
 * The opto-optical gamma the HLG path raises luminance by, for a 500 nit display.
 *
 * Equal to `1.2 + 0.42 * log10(500 / 1000)`, written as the shader writes it.
 */
@InternalFilmstripApi
public const val TONE_MAP_HLG_GAMMA: Double = 1.0735674018211279

/**
 * The power the HLG path raises luminance by to get its gain, [TONE_MAP_HLG_GAMMA] less one.
 *
 * Every channel times that gain is the same as luminance raised by the full gamma, with the colour kept.
 */
@InternalFilmstripApi
public const val TONE_MAP_HLG_EXPONENT: Double = TONE_MAP_HLG_GAMMA - 1.0

/**
 * The factor that moves the PQ knee's ratio from linear light, one at [PQ_PEAK_NITS], onto the SDR scale, one at
 * [TONE_MAP_SDR_PEAK_NITS].
 */
@InternalFilmstripApi
public const val TONE_MAP_PQ_GAIN_SCALE: Double = PQ_PEAK_NITS / TONE_MAP_SDR_PEAK_NITS

/**
 * The gamma of the OETF the tone curve encodes its SDR output with.
 *
 * media3 writes a BT.709 SDR export through a plain `pow(x, 1 / 2.2)` once the source is HDR, not the SMPTE 170M
 * curve the same transfer names on an SDR source.
 */
@InternalFilmstripApi
public const val TONE_MAP_OETF_GAMMA: Double = 2.2

/**
 * Linear BT.2020 light to CIE XYZ, D65.
 */
@InternalFilmstripApi
public val TONE_MAP_BT2020_TO_XYZ: RgbMatrix =
  RgbMatrix(
    Rgb(0.63695805, 0.14461690, 0.16888098),
    Rgb(0.26270021, 0.67799807, 0.05930172),
    Rgb(0.00000000, 0.02807269, 1.06098506),
  )

/**
 * CIE XYZ, D65, to linear BT.709 light.
 */
@InternalFilmstripApi
public val TONE_MAP_XYZ_TO_BT709: RgbMatrix =
  RgbMatrix(
    Rgb(3.24096994, -1.53738318, -0.49861076),
    Rgb(-0.96924364, 1.87596750, 0.04155506),
    Rgb(0.05563008, -0.20397696, 1.05697151),
  )

/**
 * Linear BT.2020 light to linear BT.709 light, the product of [TONE_MAP_XYZ_TO_BT709] and [TONE_MAP_BT2020_TO_XYZ].
 *
 * A BT.2020 colour outside BT.709 comes out with a channel below zero or above one, which the curve clamps after
 * the matrix.
 */
@InternalFilmstripApi
public val TONE_MAP_BT2020_TO_BT709: RgbMatrix = TONE_MAP_XYZ_TO_BT709 * TONE_MAP_BT2020_TO_XYZ

/**
 * The weights that turn linear BT.2020 light into the luminance the HLG gain is raised from.
 *
 * The luminance row of [TONE_MAP_BT2020_TO_XYZ].
 */
@InternalFilmstripApi
public val TONE_MAP_LUMA_WEIGHTS: Rgb = TONE_MAP_BT2020_TO_XYZ.green

/**
 * One cubic Hermite piece of the PQ knee, from [start] to [start] plus [width], in cd/m2.
 *
 * It rises from [from] to [to]. [startRise] and [endRise] are the tangents at each end times [width], so the piece
 * reads its position `t` from zero at [start] to one at its far end.
 */
@InternalFilmstripApi
public data class KneePiece(
  val start: Double,
  val width: Double,
  val from: Double,
  val to: Double,
  val startRise: Double,
  val endRise: Double,
) {
  /**
   * The level the piece gives [level], without checking that [level] falls inside it.
   */
  public fun at(level: Double): Double {
    val t = (level - start) / width
    return (from * (1.0 + 2.0 * t) + startRise * t) * (1.0 - t) * (1.0 - t) +
      (to * (3.0 - 2.0 * t) + endRise * (t - 1.0)) * t * t
  }
}

/**
 * The PQ knee in cd/m2, the three control points and tangents of the curve from [TONE_MAP_MASTERING_PEAK_NITS] onto
 * [TONE_MAP_SDR_PEAK_NITS].
 *
 * Light up to [x1] passes through unchanged. From [x1] to [x2] and from [x2] to the peak it follows two cubic Hermite
 * pieces, with tangent [m1] at [x1], [m2] at [x2] and [m3] at the peak. The pieces meet at [x2] with one slope, the
 * straight part and the first piece meet at [x1] with the same level but not the same slope.
 */
@InternalFilmstripApi
public object PqKnee {
  /**
   * Where the straight part ends and the first Hermite piece begins.
   */
  public const val x1: Double = TONE_MAP_SDR_PEAK_NITS * 0.75

  /**
   * The level at [x1], equal to it.
   */
  public const val y1: Double = x1

  /**
   * Where the first Hermite piece hands over to the second, halfway from [x1] to the peak.
   */
  public const val x2: Double = x1 + (TONE_MAP_MASTERING_PEAK_NITS - x1) / 2.0

  /**
   * The level at [x2], three quarters of the way from [y1] to the SDR peak.
   */
  public const val y2: Double = y1 + (TONE_MAP_SDR_PEAK_NITS - y1) * 0.75

  /**
   * The width of the first Hermite piece.
   */
  public const val h12: Double = x2 - x1

  /**
   * The width of the second Hermite piece.
   */
  public const val h23: Double = TONE_MAP_MASTERING_PEAK_NITS - x2

  /**
   * The tangent at [x1], the slope of the chord from [x1] to [x2].
   */
  public const val m1: Double = (y2 - y1) / h12

  /**
   * The tangent at the peak, the slope of the chord from [x2] to it.
   */
  public const val m3: Double = (TONE_MAP_SDR_PEAK_NITS - y2) / h23

  /**
   * The tangent at [x2], the mean of [m1] and [m3].
   */
  public const val m2: Double = (m1 + m3) / 2.0

  /**
   * The slope of the straight part, which is also the gain light with no green takes.
   */
  public const val slope: Double = y1 / x1

  /**
   * The Hermite piece from [x1] to [x2].
   */
  public val lower: KneePiece = KneePiece(x1, h12, y1, y2, h12 * m1, h12 * m2)

  /**
   * The Hermite piece from [x2] to the peak.
   */
  public val upper: KneePiece = KneePiece(x2, h23, y2, TONE_MAP_SDR_PEAK_NITS, h23 * m2, h23 * m3)
}

/**
 * The display light the PQ knee maps [nits] onto, in cd/m2.
 *
 * Light is clamped onto zero to [TONE_MAP_MASTERING_PEAK_NITS] first, then read off [PqKnee].
 */
@InternalFilmstripApi
public fun pqKneeNits(nits: Double): Double {
  val level = nits.coerceIn(0.0, TONE_MAP_MASTERING_PEAK_NITS)

  return with(PqKnee) {
    when {
      level < x1 -> level * slope
      level < x2 -> lower.at(level)
      else -> upper.at(level)
    }
  }
}

/**
 * Decodes one channel of a [transfer] [signal] to the linear light the tone curve works on.
 *
 * PQ comes out with one at [PQ_PEAK_NITS] and HLG as scene light with one at its peak.
 */
@InternalFilmstripApi
public fun toneMapLinear(
  transfer: HdrTransfer,
  signal: Double,
): Double =
  when (transfer) {
    HdrTransfer.Pq -> nitsFromPqSignal(signal) / PQ_PEAK_NITS
    HdrTransfer.Hlg -> sceneFromHlgSignal(signal)
  }

/**
 * The one factor every channel of [linear] BT.2020 light is scaled by, which also brings it onto the SDR scale.
 *
 * PQ reads the green channel in nits, maps it through [pqKneeNits] and scales by [TONE_MAP_PQ_GAIN_SCALE]. Black
 * green takes [PqKnee.slope], so light with no green still lands where its neighbours do. HLG raises
 * luminance, from [TONE_MAP_LUMA_WEIGHTS], by [TONE_MAP_HLG_EXPONENT].
 */
@InternalFilmstripApi
public fun toneMapGain(
  transfer: HdrTransfer,
  linear: Rgb,
): Double =
  when (transfer) {
    HdrTransfer.Pq -> {
      val nits = linear.g * PQ_PEAK_NITS
      val knee = if (nits > 0.0) pqKneeNits(nits) / nits else PqKnee.slope
      knee * TONE_MAP_PQ_GAIN_SCALE
    }
    HdrTransfer.Hlg -> {
      (linear dot TONE_MAP_LUMA_WEIGHTS).coerceAtLeast(0.0).pow(TONE_MAP_HLG_EXPONENT)
    }
  }

/**
 * Encodes one channel of linear BT.709 SDR light, one at full signal, as the SDR signal.
 *
 * The channel is clamped onto zero to one first, so light the matrix pushed out of gamut lands on the nearest edge.
 */
@InternalFilmstripApi
public fun toneMapOetf(linear: Double): Double = linear.coerceIn(0.0, 1.0).pow(1.0 / TONE_MAP_OETF_GAMMA)

/**
 * Tone maps a BT.2020 [transfer] [signal], each channel zero to one, to the encoded BT.709 SDR signal.
 *
 * Decodes each channel with [toneMapLinear], scales all three by [toneMapGain], converts with
 * [TONE_MAP_BT2020_TO_BT709] and encodes each channel with [toneMapOetf].
 */
@InternalFilmstripApi
public fun toneMappedSdr(
  transfer: HdrTransfer,
  signal: Rgb,
): Rgb {
  val linear = signal.map { toneMapLinear(transfer, it.coerceIn(0.0, 1.0)) }

  return (TONE_MAP_BT2020_TO_BT709 * (linear * toneMapGain(transfer, linear))).map(::toneMapOetf)
}
