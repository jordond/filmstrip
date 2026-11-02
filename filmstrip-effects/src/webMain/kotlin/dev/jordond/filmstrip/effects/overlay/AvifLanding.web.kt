package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.IDENTITY_MATRIX
import dev.jordond.filmstrip.media.NearestMatrix
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.RestatedHeif
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.YcbcrEvaluator
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.equivalentCode
import dev.jordond.filmstrip.media.evaluator
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.nearestReadable

/**
 * How the browser overlay raster hands a picture to Chrome's decoder so an AVIF's samples land the way core reads
 * them, as [avifLanding] works it out.
 */
@OptIn(InternalFilmstripApi::class)
internal sealed interface AvifLanding {
  /**
   * The picture is decoded from [bytes], through the matrix core reads it with, or through the nearest one Chrome
   * reads when [limits] holds [unreadMatrix].
   *
   * @property bytes What Chrome decodes.
   * @property limits [unreadMatrix] when Chrome does not read the matrix core reads the picture with, then
   * [eightBitDecode] when the picture is stored deeper than eight bits. Empty when neither applies.
   */
  class Decoded(
    val bytes: ByteArray,
    val limits: List<Degradation> = emptyList(),
  ) : AvifLanding

  /**
   * The picture is decoded from [bytes], which state the identity matrix at full range, so Chrome hands out its
   * stored luma, blue difference and red difference samples as green, blue and red. [evaluator] then turns each texel
   * into colour the way core reads the picture.
   *
   * @property bytes What Chrome decodes.
   * @property evaluator Decodes the eight-bit samples at the picture's own range.
   */
  class Evaluated(
    val bytes: ByteArray,
    val evaluator: YcbcrEvaluator,
  ) : AvifLanding

  /**
   * The picture is refused before Chrome sees it.
   *
   * @property message What the overlay reports.
   */
  class Refused(
    val message: String,
  ) : AvifLanding
}

/**
 * How the picture in [bytes] is handed to Chrome, from the [YcbcrCoding.reading] core reads out of it.
 *
 * An AVIF stored deeper than eight bits that Chrome decodes also reports [eightBitDecode] among its limits, after any
 * matrix it lands through, since `createImageBitmap` decodes it to eight bits a channel.
 *
 * Anything but an AVIF whose colour core knows goes through [heifWithExplicitCodestreamColour], which leaves all but an
 * AVIF or HEIC as it is. Chrome decodes no HEIC, so one is still handed over and fails to decode. Otherwise:
 * - A coding with a [YcbcrCoding.refusal] is refused with it.
 * - A reading Chrome reads at the picture's range, one whose [equivalentCode] is among [chromeMatrices] or a matrix
 * derived from primaries no named matrix matches, is decoded through it. When that is not the matrix the `nclx` names,
 * such as 12 on BT.709 primaries, the codestream and the `nclx` are restated to it.
 * - Any other reading on eight-bit 4:4:4 samples is restated to the identity matrix at full range and
 * [AvifLanding.Evaluated]. That covers YCgCo at limited range and BT.2020 constant luminance 10 and 13, which Chrome
 * does not decode at all.
 * - Any other reading lands through [nearestReadable] of [chromeMatrices] without the identity matrix, restated in the
 * codestream and the `nclx`. It is degraded by [unreadMatrix] within [NearestMatrix.withinBound] and refused past it.
 *
 * The evaluated route is a browser limit to eight bits. A canvas reads a decoded picture back at eight bits a channel,
 * so the identity matrix hands out ten-bit samples already rounded and a ten-bit 4:4:4 picture takes the nearest
 * matrix instead.
 *
 * A restate that does not apply refuses the picture, since Chrome would decode it through a matrix this cannot name. So
 * does an `irot`, `imir` and `clap` drop that is refused, with [UnturnedHeif.Refused.message], since Chrome would turn
 * a picture core turns too.
 */
@OptIn(InternalFilmstripApi::class)
internal fun avifLanding(bytes: ByteArray): AvifLanding {
  val parsed = pictureYcbcrCoding(bytes)
  val landing = colourLanding(bytes, parsed)
  val depth = parsed?.bitDepth?.takeIf { parsed.codec == CodecKind.Av1 && it > EVALUATED_DEPTH }
  if (depth == null || landing !is AvifLanding.Decoded) return landing
  return AvifLanding.Decoded(landing.bytes, landing.limits + eightBitDecode(depth))
}

// How the picture lands on Chrome's matrices, before its depth is taken into account.
@OptIn(InternalFilmstripApi::class)
private fun colourLanding(
  bytes: ByteArray,
  parsed: YcbcrCoding?,
): AvifLanding {
  val coding =
    parsed?.takeIf { it.colourKnown && it.codec == CodecKind.Av1 }
      ?: return explicitLanding(bytes)
  coding.refusal?.let { return AvifLanding.Refused(it) }
  val reading = coding.reading
  if (derivedByChrome(coding)) return explicitLanding(bytes)
  val readable = chromeMatrices(coding.fullRange).let { if (coding.unsubsampled) it else it - IDENTITY_MATRIX }
  val exact = reading.equivalentCode()?.takeIf { it in readable }
  return when {
    exact != null && coding.matrix in readable -> {
      explicitLanding(bytes)
    }
    exact != null -> {
      landing(restated(bytes, coding, exact)) { AvifLanding.Decoded(it) }
    }
    coding.unsubsampled && coding.bitDepth == EVALUATED_DEPTH -> {
      landing(restated(bytes, coding, IDENTITY_MATRIX, fullRange = true)) { identity ->
        AvifLanding.Evaluated(identity, reading.evaluator(EVALUATED_DEPTH, coding.fullRange))
      }
    }
    else -> {
      nearestLanding(bytes, coding, checkNotNull(reading.nearestReadable(readable)))
    }
  }
}

// The picture restated to nearest, degraded within the bound and refused past it.
@OptIn(InternalFilmstripApi::class)
private fun nearestLanding(
  bytes: ByteArray,
  coding: YcbcrCoding,
  nearest: NearestMatrix,
): AvifLanding {
  if (!nearest.withinBound) return AvifLanding.Refused(pastBound(nearest))
  return landing(restated(bytes, coding, nearest.matrix)) { AvifLanding.Decoded(it, listOf(unreadMatrix(nearest))) }
}

/**
 * The matrix coefficients code points Chrome decodes an AVIF through, at full range when [fullRange] is true and at
 * limited range otherwise.
 *
 * At full range that is the identity matrix 0 on 4:4:4 samples, BT.709 1, BT.601 as 5 and 6 with 2 for unspecified
 * read as BT.601, FCC 4, SMPTE 240M 7, YCgCo 8 and BT.2020 9. At limited range it is the same without YCgCo. Chrome
 * reads each as ITU-T H.273 says, except BT.709 and BT.2020 at limited range, which it turns into RGB a few eight-bit
 * codes from their H.273 reading. It decodes no constant luminance matrix and no YDzDx or ICtCp at all.
 */
internal fun chromeMatrices(fullRange: Boolean): Set<Int> =
  if (fullRange) FULL_RANGE_MATRICES else LIMITED_RANGE_MATRICES

/**
 * The cause an overlay picture reports when Chrome's AVIF decoder does not read its matrix at its range and it lands
 * through [nearest]'s, ending on [NearestMatrix.figures].
 */
@OptIn(InternalFilmstripApi::class)
internal fun unreadMatrix(nearest: NearestMatrix): Degradation =
  Degradation(
    DegradationReason.ColorSpaceConverted,
    "Chrome's AVIF decoder does not read this picture's matrix at its range, so its YCbCr samples are turned into " +
      "RGB through matrix ${nearest.matrix} instead, ${nearest.figures}.",
  )

/**
 * The cause an overlay picture stored at [bitDepth] bits a sample reports when Chrome decodes it, since
 * `createImageBitmap` hands a canvas eight bits a channel.
 */
@OptIn(InternalFilmstripApi::class)
internal fun eightBitDecode(bitDepth: Int): Degradation =
  Degradation(
    DegradationReason.PrecisionReduced,
    "Chrome decodes an AVIF into eight bits a channel, so this picture stored at $bitDepth bits a sample loses the " +
      "steps between eight-bit codes.",
  )

/**
 * What an overlay picture reports when Chrome's AVIF decoder does not read its matrix at its range and [nearest]'s,
 * the nearest one it reads, lands it past [NearestMatrix.withinBound].
 */
@OptIn(InternalFilmstripApi::class)
internal fun pastBound(nearest: NearestMatrix): String =
  "Chrome's AVIF decoder does not read this picture's matrix at its range, and the nearest one it reads, matrix " +
    "${nearest.matrix}, would turn its YCbCr samples into RGB with ${nearest.figures}."

/**
 * The reason a picture is refused when its codestream could not be restated to a matrix Chrome reads.
 */
internal const val UNRESTATED =
  "The picture's codestream could not be restated to a matrix Chrome's AVIF decoder reads, so it would be turned " +
    "into RGB through a matrix filmstrip cannot name."

/**
 * The bit depth of a 4:4:4 picture whose stored samples Chrome hands out whole under the identity matrix, since a
 * bitmap reads back at eight bits a channel.
 */
internal const val EVALUATED_DEPTH = 8

private val NOT_RESTATED = AvifLanding.Refused(UNRESTATED)

private val FULL_RANGE_MATRICES = setOf(0, 1, 2, 4, 5, 6, 7, 8, 9)
private val LIMITED_RANGE_MATRICES = FULL_RANGE_MATRICES - YCGCO

// Chrome derives a matrix from the nclx primaries itself, and reads it exactly at both ranges when no named matrix
// matches them.
@OptIn(InternalFilmstripApi::class)
private fun derivedByChrome(coding: YcbcrCoding): Boolean =
  coding.matrix == CHROMATICITY_DERIVED && coding.reading is YcbcrReading.KrKb &&
    coding.reading.equivalentCode() == null

// The picture with its codestream and nclx restated to matrix.
@OptIn(InternalFilmstripApi::class)
private fun restated(
  bytes: ByteArray,
  coding: YcbcrCoding,
  matrix: Int,
  fullRange: Boolean? = null,
): RestatedHeif {
  val primaries = coding.primaries.takeIf { it in 0..MAX_CODE_POINT } ?: UNSPECIFIED
  val restate = RestatedColour(matrix, primaries, fullRange, nclx = true)
  return heifWithRestatedColour(bytes) { _, _, _ -> restate }
}

// The picture handed to Chrome through heifWithExplicitCodestreamColour, or refused when its irot, imir and clap drop
// is.
@OptIn(InternalFilmstripApi::class)
private fun explicitLanding(bytes: ByteArray): AvifLanding =
  when (val heif = heifWithExplicitCodestreamColour(bytes)) {
    is UnturnedHeif.Refused -> refusal(heif)
    is UnturnedHeif.Handed -> AvifLanding.Decoded(heif.bytes)
  }

// What landed makes of the bytes a restate hands over, the refusal of a drop that is refused, or UNRESTATED for a
// restate that does not apply.
@OptIn(InternalFilmstripApi::class)
private inline fun landing(
  rewrite: RestatedHeif,
  landed: (ByteArray) -> AvifLanding,
): AvifLanding =
  when (rewrite) {
    is UnturnedHeif.Refused -> refusal(rewrite)
    is RestatedHeif.Unrestated -> NOT_RESTATED
    is RestatedHeif.Restated -> landed(rewrite.bytes)
  }

// The picture refused for an irot, imir and clap drop that is refused, with the sentence core gives for it.
@OptIn(InternalFilmstripApi::class)
private fun refusal(refused: UnturnedHeif.Refused): AvifLanding = AvifLanding.Refused(refused.message)

private const val CHROMATICITY_DERIVED = 12
private const val YCGCO = 8
private const val MAX_CODE_POINT = 255
private const val UNSPECIFIED = 2
