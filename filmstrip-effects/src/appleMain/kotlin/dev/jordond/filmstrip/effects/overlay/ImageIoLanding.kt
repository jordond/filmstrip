package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.IDENTITY_MATRIX
import dev.jordond.filmstrip.media.NearestMatrix
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.YcbcrEvaluator
import dev.jordond.filmstrip.media.evaluator
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.nearestReadable

/**
 * How the Apple overlay raster hands an AVIF or HEIC to ImageIO so its samples land the way core reads them, as
 * [imageIoLanding] works it out.
 */
@OptIn(InternalFilmstripApi::class)
internal sealed interface ImageIoLanding {
  /**
   * The picture is handed to ImageIO as [bytes], which decodes it through the matrix core reads it with, or through
   * the nearest one it reads when [limits] holds [unreadMatrix].
   *
   * @property bytes What ImageIO is handed.
   * @property limits Empty when ImageIO lands the picture exactly, and [unreadMatrix] otherwise.
   */
  class Decoded(
    val bytes: ByteArray,
    val limits: List<Degradation> = emptyList(),
  ) : ImageIoLanding

  /**
   * The picture is handed to ImageIO as [bytes], which state the identity matrix at full range, so ImageIO hands out
   * its stored luma, blue difference and red difference samples as green, blue and red. [evaluator] then turns each
   * texel into colour the way core reads the picture.
   *
   * @property bytes What ImageIO is handed.
   * @property evaluator Decodes the samples at the picture's own depth and range.
   * @property bitDepth The depth of the picture's samples, and of the ones ImageIO hands out.
   */
  class Evaluated(
    val bytes: ByteArray,
    val evaluator: YcbcrEvaluator,
    val bitDepth: Int,
  ) : ImageIoLanding

  /**
   * The picture is refused before ImageIO sees it.
   *
   * @property message What the overlay reports.
   */
  class Refused(
    val message: String,
  ) : ImageIoLanding
}

/**
 * How the picture in [bytes] is handed to ImageIO, from the [YcbcrCoding.reading] core reads out of it.
 *
 * A picture core reads no coding for, or whose colour it does not know, is handed over through
 * [heifWithExplicitCodestreamColour]. Otherwise:
 * - A coding with a [YcbcrCoding.refusal] is refused with it.
 * - A reading that one of [IMAGE_IO_MATRICES] reads exactly is decoded through it. When that is not the matrix the
 * `nclx` names, such as 12 on BT.709 primaries, the codestream and the `nclx` are restated to it. The identity matrix
 * counts only for 4:4:4 samples at full range and a depth in [EVALUATED_DEPTHS], so a twelve-bit 4:4:4 picture under
 * it lands like any other reading through the remaining matrices.
 * - Any other reading on 4:4:4 samples at a depth in [EVALUATED_DEPTHS] is restated to the identity matrix at full
 * range and [ImageIoLanding.Evaluated]. That covers FCC 4, SMPTE 240M 7, YCgCo 8, BT.2020 constant luminance 10 and
 * 13, 12 on primaries no named matrix matches, and the identity matrix at limited range, which ImageIO hands out no
 * samples for.
 * - Any other reading lands through [nearestReadable] of [IMAGE_IO_MATRICES] without the identity matrix, restated in
 * the codestream and the `nclx`. It is degraded by [unreadMatrix] within [NearestMatrix.withinBound] and refused past
 * it.
 *
 * A restate that does not apply refuses the picture, since ImageIO would decode it through a matrix this cannot name.
 */
@OptIn(InternalFilmstripApi::class)
internal fun imageIoLanding(bytes: ByteArray): ImageIoLanding {
  val coding =
    pictureYcbcrCoding(bytes)?.takeIf { it.colourKnown && it.codec != CodecKind.Other }
      ?: return ImageIoLanding.Decoded(heifWithExplicitCodestreamColour(bytes))
  coding.refusal?.let { return ImageIoLanding.Refused(it) }
  val reading = coding.reading
  val evaluated = coding.bitDepth?.takeIf { coding.unsubsampled && it in EVALUATED_DEPTHS }
  val readable = if (evaluated != null && coding.fullRange) IMAGE_IO_MATRICES else IMAGE_IO_MATRICES - IDENTITY_MATRIX
  val nearest = checkNotNull(reading.nearestReadable(readable))
  return when {
    nearest.exact && coding.matrix in readable -> {
      ImageIoLanding.Decoded(heifWithExplicitCodestreamColour(bytes))
    }
    nearest.exact -> {
      ImageIoLanding.Decoded(restated(bytes, coding, nearest.matrix) ?: return NOT_RESTATED)
    }
    evaluated != null -> {
      val identity = restated(bytes, coding, IDENTITY_MATRIX, fullRange = true) ?: return NOT_RESTATED
      ImageIoLanding.Evaluated(identity, reading.evaluator(evaluated, coding.fullRange), evaluated)
    }
    !nearest.withinBound -> {
      ImageIoLanding.Refused(pastBound(nearest))
    }
    else -> {
      val landed = restated(bytes, coding, nearest.matrix) ?: return NOT_RESTATED
      ImageIoLanding.Decoded(landed, listOf(unreadMatrix(nearest)))
    }
  }
}

/**
 * The cause an overlay picture reports when Apple's HEIF decoder does not read its matrix and it lands through
 * [nearest]'s, ending on [NearestMatrix.figures].
 *
 * ImageIO decodes through the identity matrix at full range on 4:4:4 samples at a depth in [EVALUATED_DEPTHS], BT.601
 * as 5 and 6, BT.709 and BT.2020, with 2 for unspecified decoded as BT.601. It takes a HEIC's matrix from the `nclx`
 * and its range from the codestream, and hands out none of a picture's samples under the identity matrix at limited
 * range, or of a twelve-bit HEIC's at limited range. A picture under any other matrix that is not 4:4:4 at a depth in
 * [EVALUATED_DEPTHS] is restated to the nearest matrix it reads.
 */
@InternalFilmstripApi
public fun unreadMatrix(nearest: NearestMatrix): Degradation =
  Degradation(
    DegradationReason.ColorSpaceConverted,
    "Apple's HEIF decoder does not read this picture's matrix, so its YCbCr samples are turned into RGB through " +
      "matrix ${nearest.matrix} instead, ${nearest.figures}.",
  )

/**
 * What an overlay picture reports when Apple's HEIF decoder does not read its matrix and [nearest]'s, the nearest one
 * it reads, lands it past [NearestMatrix.withinBound].
 */
internal fun pastBound(nearest: NearestMatrix): String =
  "Apple's HEIF decoder does not read this picture's matrix, and the nearest one it reads, matrix " +
    "${nearest.matrix}, would turn its YCbCr samples into RGB with ${nearest.figures}."

/**
 * The matrix coefficients code points ImageIO decodes a HEIF through as ITU-T H.273 says, the identity matrix 0 only
 * at full range on 4:4:4 samples at a depth in [EVALUATED_DEPTHS] and 2 for unspecified as BT.601.
 */
internal val IMAGE_IO_MATRICES: Set<Int> = setOf(0, 1, 2, 5, 6, 9)

/**
 * The bit depths of a 4:4:4 picture whose stored samples ImageIO hands out under the identity matrix, each in a
 * buffer [evaluatedCodes] reads.
 */
internal val EVALUATED_DEPTHS: Set<Int> = setOf(8, 10)

/**
 * The reason a picture is refused when its codestream could not be restated to a matrix ImageIO reads.
 */
internal const val UNRESTATED =
  "The picture's codestream could not be restated to a matrix Apple's HEIF decoder reads, so it would be turned " +
    "into RGB through a matrix filmstrip cannot name."

private val NOT_RESTATED = ImageIoLanding.Refused(UNRESTATED)

// The picture with its codestream and nclx restated to matrix, or null when the restate does not apply.
@OptIn(InternalFilmstripApi::class)
private fun restated(
  bytes: ByteArray,
  coding: YcbcrCoding,
  matrix: Int,
  fullRange: Boolean? = null,
): ByteArray? {
  val primaries = coding.primaries.takeIf { it in 0..MAX_CODE_POINT } ?: UNSPECIFIED
  val restate = RestatedColour(matrix, primaries, fullRange, nclx = true)
  return heifWithRestatedColour(bytes) { _, _, _ -> restate }.takeIf { it !== bytes }
}

private const val MAX_CODE_POINT = 255
private const val UNSPECIFIED = 2
