package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.NearestMatrix
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.heif.RestatedColor

/**
 * How Android's HEIC or AVIF decoder turns a picture's YCbCr samples into RGB, and how that falls short of the
 * picture's own reading.
 *
 * @property matrix The ITU-T H.273 matrix the picture lands through, the [NearestMatrix.matrix] [decoderConversionOf]
 * finds.
 * @property fullRange Whether the picture lands at full range.
 * @property limits What the overlay reports for each way the landing falls short, empty when it lands as its file
 * records.
 * @property refusal What the overlay reports when the nearest matrix the decoder reads lands the picture past
 * [NearestMatrix.withinBound], so it is refused rather than drawn, or null when it is drawn.
 */
internal class DecoderConversion(
  val matrix: Int,
  val fullRange: Boolean,
  val limits: List<DecoderLimit>,
  val refusal: String? = null,
)

/**
 * The ITU-T H.273 matrix coefficients code points Android's HEIC or AVIF decoder on API level [sdk] reads at
 * [fullRange], each beside the primaries [pairedPrimaries] gives for it.
 *
 * The HEIC decoder, and the AVIF decoder on API 33 to 35, take the matrix and range from the codestream whatever the
 * `nclx` records. The AVIF decoder on API 36 takes both from the `nclx` of a picture that has one. The AVIF decoder
 * before API 33 reads no codestream at all and lands every picture through BT.601 at limited range.
 *
 * BT.601 is read on every API level, as 5, 6 and the unspecified 2 alike, and is given here as 6. BT.2020 is read from
 * API 33. BT.709 is read from API 33 at full range and from API 34 at limited range, from API 35 in a [tenBit] AVIF.
 * From API 36 the AVIF decoder also reads FCC 4 and SMPTE 240M 7, and YCgCo 8 at full range in an eight-bit picture.
 * No API level reads the identity matrix 0.
 *
 * The HEIC side is measured on API 28, 29, 30, 31, 33, 34, 35 and 36, and the AVIF side on API 31, 33, 34, 35 and 36.
 * API 32 is given API 31's.
 *
 * @param avif Whether the picture is AV1 coded rather than HEVC coded.
 * @param tenBit Whether the picture's samples are ten bits deep, which moves the API levels an AVIF's BT.709 and YCgCo
 * are read from.
 */
internal fun readableMatrices(
  fullRange: Boolean,
  sdk: Int,
  avif: Boolean,
  tenBit: Boolean,
): Set<Int> =
  buildSet {
    add(BT601)
    val bt709LimitedFrom = if (avif && tenBit) TEN_BIT_BT709_LIMITED_API else BT709_LIMITED_API
    if (sdk >= bt709LimitedFrom || (fullRange && sdk >= WIDE_MATRIX_API)) add(BT709)
    if (sdk >= WIDE_MATRIX_API) add(BT2020)
    if (avif && sdk >= AVIF_MORE_MATRICES_API) {
      addAll(AVIF_MORE_MATRICES)
      if (fullRange && !tenBit) add(YCGCO)
    }
  }

/**
 * The matrix, primaries and range [forAndroidDecoder] has a HEIC or AVIF state for a picture whose own samples read as
 * [reading] at [fullRange], or null when [reading] is [YcbcrReading.Refused].
 *
 * The matrix is the [nearestReadable] of [readableMatrices] for samples at [fullRange] read at the range
 * [decoderFullRange] gives, beside the primaries [pairedPrimaries] gives for it, and the range is the one
 * [decoderFullRange] gives, the range the decoder lands the picture at. The `nclx` of the primary item and its tiles
 * states them too, since the AVIF decoder on API 36 takes the matrix and range from it.
 *
 * @param avif Whether the picture is AV1 coded rather than HEVC coded.
 * @param tenBit Whether the picture's samples are ten bits deep.
 */
internal fun restatedForDecoder(
  reading: YcbcrReading,
  fullRange: Boolean,
  sdk: Int,
  avif: Boolean,
  tenBit: Boolean,
): RestatedColor? {
  val readable = readableMatrices(fullRange, sdk, avif, tenBit)
  val landedFullRange = decoderFullRange(fullRange, sdk, avif)
  val nearest = reading.nearestReadable(readable, fullRange, landedFullRange) ?: return null
  return RestatedColor(nearest.matrix, pairedPrimaries(nearest.matrix), landedFullRange, nclx = true)
}

/**
 * Whether Android's HEIC or AVIF decoder on API level [sdk] reads a picture whose samples are at [fullRange] as full
 * range. The HEIC decoder before API 29 and the AVIF decoder before API 33 read every picture at limited range, and
 * every other reads the range a picture states.
 *
 * @param avif Whether the picture is AV1 coded rather than HEVC coded.
 */
internal fun decoderFullRange(
  fullRange: Boolean,
  sdk: Int,
  avif: Boolean,
): Boolean = fullRange && sdk >= if (avif) WIDE_MATRIX_API else HEIC_FULL_RANGE_API

/**
 * How Android's HEIC or AVIF decoder on API level [sdk] lands a picture whose samples read as [reading] at
 * [fullRange], once [restatedForDecoder] has it state the matrix it lands through. Null when [reading] is
 * [YcbcrReading.Refused].
 *
 * It lands through the [nearestReadable] of [readableMatrices] from the picture's own range to the one
 * [decoderFullRange] gives. The AVIF decoder before API 33 and the HEIC decoder before API 29 land every picture at
 * limited range, so a full range one there reports [DecoderLimit.AvifBeforeApi33] or [DecoderLimit.HeicBeforeApi29] and
 * its landing is measured with the range moved as well. A matrix that reads the same as the picture's own reports no
 * matrix limit, and any other reports [DecoderLimit.LandedThrough].
 * Either is refused when the whole landing is past [NearestMatrix.withinBound].
 *
 * @param avif Whether the picture is AV1 coded rather than HEVC coded.
 * @param tenBit Whether the picture's samples are ten bits deep, which moves the API levels an AVIF's BT.709 and YCgCo
 * are read from.
 */
internal fun decoderConversionOf(
  reading: YcbcrReading,
  fullRange: Boolean,
  sdk: Int,
  avif: Boolean,
  tenBit: Boolean,
): DecoderConversion? {
  val readable = readableMatrices(fullRange, sdk, avif, tenBit)
  val landedFullRange = decoderFullRange(fullRange, sdk, avif)
  val nearest = reading.nearestReadable(readable, fullRange, landedFullRange) ?: return null
  val ownMatrix = reading.nearestReadable(readable)?.takeIf { it.exact }?.matrix == nearest.matrix
  val limits =
    listOfNotNull(
      DecoderLimit.LandedThrough(avif, nearest).takeUnless { ownMatrix },
      (if (avif) DecoderLimit.AvifBeforeApi33 else DecoderLimit.HeicBeforeApi29).takeIf {
        fullRange != landedFullRange
      },
    )
  val refusal = pastBound(avif, nearest).takeUnless { nearest.withinBound }
  return DecoderConversion(nearest.matrix, landedFullRange, limits, refusal)
}

/**
 * What an overlay picture reports when Android's HEIC or AVIF decoder does not read its matrix and [nearest]'s, the
 * nearest one it reads, lands it past [NearestMatrix.withinBound].
 *
 * @param avif Whether the picture is AV1 coded rather than HEVC coded.
 */
internal fun pastBound(
  avif: Boolean,
  nearest: NearestMatrix,
): String =
  "${decoderName(avif)} on this API level does not read this picture's matrix, and the nearest one it reads, " +
    "matrix ${nearest.matrix}, would turn its YCbCr samples into RGB with ${nearest.figures}."

/**
 * How Android's decoder on API level [sdk] lands a picture whose coding, read before [forAndroidDecoder] rewrote it,
 * is this, as [decoderConversionOf] gives it for [reading], or null for a picture that is neither HEVC nor AV1 coded.
 *
 * When [restated] is true the codestream was rewritten to state what [restatedForDecoder] gives, so the picture lands
 * at [handedFullRange]. Otherwise the decoder reads the codestream as it was. The picture then lands as
 * [decoderConversionOf] says only when that codestream states its matrix and range, the matrix reads as the one
 * [decoderConversionOf] lands through, beside the primaries [pairedPrimaries] gives for it, and an `nclx` records the
 * same matrix and range. The picture's own range is [handedFullRange] when core called the restate and the codestream's
 * otherwise. Null for anything else, which is left to the decoder.
 *
 * @param handedFullRange The full range flag core handed the restate, or null when it did not call it.
 * @param restated Whether the rewrite changed the picture.
 */
internal fun YcbcrCoding.decoderConversionOf(
  sdk: Int,
  handedFullRange: Boolean?,
  restated: Boolean,
): DecoderConversion? {
  if (codec != CodecKind.Av1 && codec != CodecKind.Hevc) return null
  if (restated) return handedFullRange?.let { decoderConversionOf(reading, it, sdk, avif, tenBitSamples) }
  val stated = codestream ?: return null
  val statedMatrix = stated.matrix ?: return null
  val statedFullRange = stated.fullRange ?: return null
  val nclxAgrees = !fromNclx || (matrix == statedMatrix && fullRange == statedFullRange)
  val ownFullRange = handedFullRange ?: statedFullRange.takeIf { nclxAgrees } ?: return null
  val conversion = decoderConversionOf(reading, ownFullRange, sdk, avif, tenBitSamples) ?: return null
  val readsAs = if (statedMatrix in BT601_MATRICES) BT601 else statedMatrix
  val asStated =
    readsAs == conversion.matrix && ownFullRange == statedFullRange && stated.primaries == pairedPrimaries(statedMatrix)
  return conversion.takeIf { asStated }
}

/**
 * What an overlay picture core reads as this coding reports when its codestream reaches Android's decoder on API level
 * [sdk] as it was, without [restatedForDecoder]'s matrix, and the nearest matrix the decoder reads lands it past
 * [NearestMatrix.withinBound]. Null when that landing is within the bound or this coding is neither HEVC nor AV1 coded.
 *
 * Whatever matrix the decoder then reads the codestream through is one of [readableMatrices], so it lands the picture
 * at least as far off as the nearest of them. The picture's own range is [handedFullRange] when core called the
 * restate and [YcbcrCoding.fullRange] otherwise.
 *
 * @param handedFullRange The full range flag core handed the restate, or null when it did not call it.
 */
internal fun YcbcrCoding.unrestatedRefusal(
  sdk: Int,
  handedFullRange: Boolean?,
): String? {
  if (codec != CodecKind.Av1 && codec != CodecKind.Hevc) return null
  return decoderConversionOf(reading, handedFullRange ?: fullRange, sdk, avif, tenBitSamples)?.refusal
}

/**
 * Whether this coding's samples are exactly ten bits deep, the depth that moves the API levels Android's AVIF decoder
 * reads BT.709 and YCgCo from.
 *
 * False for any other depth and an unknown one. A twelve-bit AVIF, which the decoder draws only from API 36, lands
 * there as an eight-bit one does.
 */
internal val YcbcrCoding.tenBitSamples: Boolean
  get() = bitDepth == TEN_BITS

/**
 * Whether this coding is an AV1 coded picture, which Android hands to its AVIF decoder rather than its HEIC one.
 */
internal val YcbcrCoding.avif: Boolean
  get() = codec == CodecKind.Av1

/**
 * Whether this coding is an [avif] picture whose [tenBitSamples] is true, the one Android's AVIF decoder on API 33
 * hands back no frame for.
 */
internal val YcbcrCoding.tenBitAvif: Boolean
  get() = avif && tenBitSamples

/**
 * Whether this coding is an [avif] picture whose samples are twelve bits deep, the one Android's AVIF decoder draws
 * only from API 36.
 */
internal val YcbcrCoding.twelveBitAvif: Boolean
  get() = avif && bitDepth == TWELVE_BITS

/**
 * Whether this coding is an HEVC coded picture whose samples are twelve bits deep, the one Android's HEIC decoder
 * hands back no frame for on every API level it decodes HEIC on.
 */
internal val YcbcrCoding.twelveBitHeic: Boolean
  get() = codec == CodecKind.Hevc && bitDepth == TWELVE_BITS

/**
 * What an overlay picture core reads as [coding] reports when it is refused before Android's decoder on API level [sdk]
 * sees it, or null when it is handed over.
 *
 * A coding with a [YcbcrCoding.refusal] is refused with it on every level. A [YcbcrCoding.twelveBitAvif] picture is
 * refused as [DecoderRefusal.AvifTwelveBit] from API 31, where Android first decodes AVIF, to API 35. Below API 31 it
 * reaches the decoder, which hands back no frame for any AVIF.
 */
internal fun refusalBeforeDecoder(
  coding: YcbcrCoding?,
  sdk: Int,
): String? =
  when {
    coding == null -> null
    coding.refusal != null -> coding.refusal
    coding.twelveBitAvif && sdk in AVIF_API until TWELVE_BIT_AVIF_API -> DecoderRefusal.AvifTwelveBit.message
    else -> null
  }

/**
 * Why Android's decoder on API level [sdk] hands back no frame for a picture core reads as [coding].
 *
 * It is [DecoderRefusal.AvifTenBitNoFrame] for a [YcbcrCoding.tenBitAvif] picture on API 33,
 * [DecoderRefusal.HeicTwelveBitNoFrame] for a [YcbcrCoding.twelveBitHeic] picture from API 28, where Android first
 * decodes HEIC, and [DecoderRefusal.NoFrame] for any other picture, including one core reads no YCbCr coding for.
 */
internal fun noFrameRefusal(
  coding: YcbcrCoding?,
  sdk: Int,
): DecoderRefusal =
  when {
    coding?.tenBitAvif == true && sdk == WIDE_MATRIX_API -> DecoderRefusal.AvifTenBitNoFrame
    coding?.twelveBitHeic == true && sdk >= HEIC_API -> DecoderRefusal.HeicTwelveBitNoFrame
    else -> DecoderRefusal.NoFrame
  }

/**
 * The primaries Android's HEIC and AVIF decoders pair with the ITU-T H.273 [matrix]: BT.709 primaries with BT.709,
 * BT.2020 primaries with BT.2020 and BT.601 primaries with every other matrix.
 */
internal fun pairedPrimaries(matrix: Int): Int =
  when (matrix) {
    BT709, BT2020 -> matrix
    else -> BT601
  }

private const val BT709 = 1
private const val BT601 = 6
private const val YCGCO = 8
private const val BT2020 = 9
private const val TEN_BITS = 10
private const val TWELVE_BITS = 12
private val BT601_MATRICES = setOf(2, 5, BT601)
private val AVIF_MORE_MATRICES = setOf(4, 7)
private const val HEIC_API = 28
private const val HEIC_FULL_RANGE_API = 29
private const val AVIF_API = 31
private const val WIDE_MATRIX_API = 33
private const val BT709_LIMITED_API = 34
private const val TEN_BIT_BT709_LIMITED_API = 35
private const val AVIF_MORE_MATRICES_API = 36
private const val TWELVE_BIT_AVIF_API = 36
