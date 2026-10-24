package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

// The YCbCr equations of ITU-T H.273 section 8.3 in both directions, behind ycbcrToRgb, rgbMatrix and nearestReadable.

/**
 * Decodes YCbCr samples of one bit depth at full or limited range to red, green and blue under one [YcbcrReading], as
 * [YcbcrReading.evaluator] prepares it.
 *
 * The matrix of a linear reading and the divisors of a constant luminance one are worked out when it is made, so
 * decoding a sample costs only the arithmetic [ycbcrToRgb] would do on it.
 */
@InternalFilmstripApi
public class YcbcrEvaluator internal constructor(
  reading: YcbcrReading,
  private val range: SampleRange,
) {
  init {
    require(reading !is YcbcrReading.Refused) { (reading as YcbcrReading.Refused).reason }
  }

  private val matrix: YcbcrMatrix? = linearMatrix(reading, range)
  private val constant: YcbcrReading.ConstantLuminance? = reading as? YcbcrReading.ConstantLuminance
  private val divisors: ConstantLuminanceDivisors? = constant?.divisors()

  /**
   * The red, green and blue, each clamped onto zero to one, that the samples [y], [cb] and [cr] decode to, written into
   * [into] and returned.
   *
   * @param into Where the three channels are written, so a caller decoding many samples can reuse one array.
   */
  public fun rgb(
    y: Int,
    cb: Int,
    cr: Int,
    into: DoubleArray = DoubleArray(3),
  ): DoubleArray = rgb(y.toDouble(), cb.toDouble(), cr.toDouble(), into)

  internal fun rgb(
    y: Double,
    cb: Double,
    cr: Double,
    into: DoubleArray = DoubleArray(3),
  ): DoubleArray {
    val linear = matrix
    if (linear != null) {
      val c = linear.coefficients
      for (row in 0 until 3) {
        into[row] = c[row * 3] * y + c[row * 3 + 1] * cb + c[row * 3 + 2] * cr + linear.offsets[row]
      }
    } else {
      checkNotNull(constant).rgb(y, cb, cr, range, checkNotNull(divisors), into)
    }
    for (channel in 0 until 3) into[channel] = into[channel].coerceIn(0.0, 1.0)
    return into
  }
}

/**
 * Where luma samples start and how far they span, and where chroma samples centre and how far they span, for samples
 * of one bit depth at full or limited range.
 */
internal class SampleRange(
  val lumaFoot: Double,
  val lumaSpan: Double,
  val chromaMiddle: Double,
  val chromaSpan: Double,
)

internal fun sampleRange(
  bitDepth: Int,
  fullRange: Boolean,
): SampleRange {
  require(bitDepth in MIN_BIT_DEPTH..MAX_BIT_DEPTH) { "bit depth $bitDepth is outside 8 to 16" }
  val max = ((1 shl bitDepth) - 1).toDouble()
  val scale = (1 shl (bitDepth - MIN_BIT_DEPTH)).toDouble()
  val middle = (1 shl (bitDepth - 1)).toDouble()
  return if (fullRange) {
    SampleRange(0.0, max, middle, max)
  } else {
    SampleRange(LUMA_FOOT * scale, LUMA_SPAN * scale, middle, CHROMA_SPAN * scale)
  }
}

// The constant luminance inverse of H.273 section 8.3, with each difference's divisor picked by its sign.
private fun YcbcrReading.ConstantLuminance.rgb(
  y: Double,
  cb: Double,
  cr: Double,
  range: SampleRange,
  divisors: ConstantLuminanceDivisors,
  into: DoubleArray,
) {
  val luma = (y - range.lumaFoot) / range.lumaSpan
  val blueDifference = (cb - range.chromaMiddle) / range.chromaSpan
  val redDifference = (cr - range.chromaMiddle) / range.chromaSpan
  val blue = luma + blueDifference * if (blueDifference <= 0) 2 * divisors.nb else 2 * divisors.pb
  val red = luma + redDifference * if (redDifference <= 0) 2 * divisors.nr else 2 * divisors.pr
  val linearGreen = (oetf.decode(luma) - kr * oetf.decode(red) - kb * oetf.decode(blue)) / (1 - kr - kb)
  into[0] = red
  into[1] = oetf.encode(linearGreen)
  into[2] = blue
}

/**
 * The reach of a constant luminance reading's blue and red differences below zero, [nb] and [nr], and above it, [pb]
 * and [pr], each half the divisor H.273 section 8.3 gives that side.
 */
internal class ConstantLuminanceDivisors(
  val nb: Double,
  val pb: Double,
  val nr: Double,
  val pr: Double,
)

internal fun YcbcrReading.ConstantLuminance.divisors(): ConstantLuminanceDivisors =
  ConstantLuminanceDivisors(
    nb = oetf.encode(1 - kb),
    pb = 1 - oetf.encode(kb),
    nr = oetf.encode(1 - kr),
    pr = 1 - oetf.encode(kr),
  )

// The forward equations of H.273 section 8.3, to samples left unrounded.
internal fun encode(
  reading: YcbcrReading,
  rgb: DoubleArray,
  range: SampleRange,
): DoubleArray {
  val (r, g, b) = rgb.toList()

  fun luma(value: Double) = range.lumaFoot + value * range.lumaSpan

  fun chroma(value: Double) = range.chromaMiddle + value * range.chromaSpan

  fun lumaScaled(value: Double) = range.chromaMiddle + value * range.lumaSpan
  return when (reading) {
    is YcbcrReading.Refused -> {
      throw IllegalArgumentException(reading.reason)
    }
    YcbcrReading.Identity -> {
      doubleArrayOf(luma(g), luma(b), luma(r))
    }
    YcbcrReading.YCgCo -> {
      doubleArrayOf(luma(0.5 * g + 0.25 * (r + b)), lumaScaled(0.5 * g - 0.25 * (r + b)), lumaScaled(0.5 * (r - b)))
    }
    is YcbcrReading.KrKb -> {
      val (kr, kb) = reading
      val y = kr * r + (1 - kr - kb) * g + kb * b
      doubleArrayOf(luma(y), chroma((b - y) / (2 * (1 - kb))), chroma((r - y) / (2 * (1 - kr))))
    }
    is YcbcrReading.ConstantLuminance -> {
      val oetf = reading.oetf
      val linear =
        reading.kr * oetf.decode(r) + (1 - reading.kr - reading.kb) * oetf.decode(g) +
          reading.kb * oetf.decode(b)
      val y = oetf.encode(linear)
      val divisors = reading.divisors()
      val blue = b - y
      val red = r - y
      doubleArrayOf(
        luma(y),
        chroma(blue / (2 * if (blue <= 0) divisors.nb else divisors.pb)),
        chroma(red / (2 * if (red <= 0) divisors.nr else divisors.pr)),
      )
    }
  }
}

// The decode of a linear reading on normalised luma and differences, folded onto the samples' foot, middle and spans.
internal fun linearMatrix(
  reading: YcbcrReading,
  range: SampleRange,
): YcbcrMatrix? {
  val differenceOrigins = doubleArrayOf(range.lumaFoot, range.chromaMiddle, range.chromaMiddle)
  val (normalised, origins, spans) =
    when (reading) {
      is YcbcrReading.ConstantLuminance, is YcbcrReading.Refused -> {
        return null
      }
      YcbcrReading.Identity -> {
        Triple(
          doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
          DoubleArray(3) { range.lumaFoot },
          DoubleArray(3) { range.lumaSpan },
        )
      }
      YcbcrReading.YCgCo -> {
        Triple(
          doubleArrayOf(1.0, -1.0, 1.0, 1.0, 1.0, 0.0, 1.0, -1.0, -1.0),
          differenceOrigins,
          DoubleArray(3) { range.lumaSpan },
        )
      }
      is YcbcrReading.KrKb -> {
        val (kr, kb) = reading
        val kg = 1 - kr - kb
        Triple(
          doubleArrayOf(
            1.0,
            0.0,
            2 * (1 - kr),
            1.0,
            -2 * kb * (1 - kb) / kg,
            -2 * kr * (1 - kr) / kg,
            1.0,
            2 * (1 - kb),
            0.0,
          ),
          differenceOrigins,
          doubleArrayOf(range.lumaSpan, range.chromaSpan, range.chromaSpan),
        )
      }
    }
  val coefficients = DoubleArray(9) { normalised[it] / spans[it % 3] }
  val offsets = DoubleArray(3) { row -> -(0 until 3).sumOf { coefficients[row * 3 + it] * origins[it] } }
  return YcbcrMatrix(coefficients, offsets)
}

private const val MIN_BIT_DEPTH = 8
private const val MAX_BIT_DEPTH = 16
private const val LUMA_FOOT = 16
private const val LUMA_SPAN = 219
private const val CHROMA_SPAN = 224
