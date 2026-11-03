package dev.jordond.filmstrip.media3.internal.encoder

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import dev.jordond.filmstrip.color.rgbMatrix
import dev.jordond.filmstrip.color.ycbcrReading
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the color an encoder writes when it is asked for one.
 *
 * An encoder fed RGB through its input surface picks the matrix and range it converts with, and not every encoder
 * picks the ones it was configured with.
 */
internal fun interface EncoderColorProbe {
  /**
   * The color the encoder [request] names writes when asked for [EncoderColorRequest.requested], or why that could
   * not be told.
   */
  fun written(request: EncoderColorRequest): WrittenColor
}

/**
 * One encoder at one frame size tier, asked for one color.
 *
 * @property encoder The encoder's codec name.
 * @property mimeType The MIME type it encodes.
 * @property tier The size tier of the frames the export encodes.
 * @property requested The color media3 asks it for.
 */
internal data class EncoderColorRequest(
  val encoder: String,
  val mimeType: String,
  val tier: SizeTier,
  val requested: ColorInfo,
)

/**
 * A band of frame sizes measured as one, at the largest size in the band the encoder takes.
 *
 * A frame belongs to the first tier whose [width] its longer side and whose [height] its shorter side fit within, so a
 * portrait frame shares the tier of the landscape one it turns into. Every frame past [HD] belongs to [UHD].
 *
 * @property width The largest width in the tier.
 * @property height The largest height in the tier.
 */
internal enum class SizeTier(
  val width: Int,
  val height: Int,
) {
  SD(720, 576),
  HD(1920, 1080),
  UHD(3840, 2160),
  ;

  companion object {
    /**
     * The tier a frame belongs to, comparing its longer side to each tier's width and its shorter side to its height.
     *
     * @param width The frame's width.
     * @param height The frame's height.
     */
    fun of(
      width: Int,
      height: Int,
    ): SizeTier {
      val longer = max(width, height)
      val shorter = min(width, height)
      return entries.firstOrNull { longer <= it.width && shorter <= it.height } ?: UHD
    }
  }
}

/**
 * What an [EncoderColorProbe] found an encoder writes.
 */
internal sealed interface WrittenColor {
  /**
   * The encoder was measured writing [color].
   */
  data class Measured(
    val color: ColorInfo,
  ) : WrittenColor

  /**
   * The color could not be told, for [reason].
   *
   * @property reason Why the color could not be told.
   * @property kept Whether [MeasuredEncoderColor] keeps the answer for the process. An answer cut short by a deadline
   * is not kept, so the next caller measures again.
   */
  data class Unclear(
    val reason: String,
    val kept: Boolean = true,
  ) : WrittenColor {
    companion object {
      /**
       * The answer for a measurement the deadline cut short before it got as far as [reason] says, which is not kept.
       *
       * @param reason How far the measurement got.
       */
      fun outOfTime(reason: String): Unclear = Unclear(reason, kept = false)
    }
  }
}

/**
 * The color among BT.601 and BT.709 at limited and full range whose reading turns [codes] nearest full green, with
 * [requested]'s transfer, or null when even that reading lands further than [GREEN_TOLERANCE] from it.
 *
 * Each candidate reads [codes] through core's H.273 matrix for code 6 or 1 at its range, without clamping the RGB it
 * gives.
 *
 * @param codes The eight-bit Y, Cb and Cr an encoder wrote for full green.
 */
internal fun nearestColor(
  codes: Triple<Int, Int, Int>,
  requested: ColorInfo,
): ColorInfo? {
  val (nearest, distance) =
    CANDIDATES
      .map { candidate -> candidate to fromGreen(codes, candidate) }
      .minBy { it.second }
  if (distance > GREEN_TOLERANCE) return null
  return requested
    .buildUpon()
    .setColorSpace(nearest.colorSpace)
    .setColorRange(nearest.colorRange)
    .build()
}

/**
 * How far [codes] read through [candidate]'s matrix and range land from full green, as the largest of the three
 * channels' distances on a zero to one scale.
 */
internal fun fromGreen(
  codes: Triple<Int, Int, Int>,
  candidate: GreenCandidate,
): Double {
  val matrix = if (candidate.colorSpace == C.COLOR_SPACE_BT601) BT601_MATRIX else BT709_MATRIX
  val reading = ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED)
  val decode = checkNotNull(reading.rgbMatrix(EIGHT_BIT, candidate.colorRange == C.COLOR_RANGE_FULL))
  val (red, green, blue) = decode.rgb(codes.first.toDouble(), codes.second.toDouble(), codes.third.toDouble())
  return maxOf(abs(red), abs(green - 1.0), abs(blue))
}

/**
 * One matrix and range [nearestColor] weighs.
 *
 * @property colorSpace media3's color space, BT.601 or BT.709.
 * @property colorRange media3's color range, limited or full.
 */
internal data class GreenCandidate(
  val colorSpace: Int,
  val colorRange: Int,
)

/**
 * BT.601 and BT.709, each at limited and full range.
 */
internal val CANDIDATES: List<GreenCandidate> =
  listOf(C.COLOR_SPACE_BT601, C.COLOR_SPACE_BT709).flatMap { space ->
    listOf(C.COLOR_RANGE_LIMITED, C.COLOR_RANGE_FULL).map { range -> GreenCandidate(space, range) }
  }

// The nearest wrong reading of any candidate's green lands about 0.068 away. Two codes of noise on each channel move
// a right reading less than 0.031.
internal const val GREEN_TOLERANCE = 0.034

private const val BT709_MATRIX = 1
private const val BT601_MATRIX = 6
private const val UNSPECIFIED = 2
private const val EIGHT_BIT = 8
