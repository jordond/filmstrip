package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.embeddedColour
import dev.jordond.filmstrip.internal.heifWithExplicitColour
import dev.jordond.filmstrip.internal.isJpeg

/**
 * Whether [bytes] are a JPEG whose embedded ICC profile is for RGB or grey channels.
 *
 * False for any other picture, a JPEG without a whole profile, and one whose profile is for CMYK or any other colour
 * space.
 */
@InternalFilmstripApi
public fun jpegProfileIsRgbOrGrey(bytes: ByteArray): Boolean =
  isJpeg(bytes) &&
    embeddedColour(bytes)?.descriptions?.any { it is Icc && iccChannels(it.profile) != null } == true

/**
 * [bytes] with the codestream of an AVIF or HEIC's primary item stating the colour it lands with, so a decoder that
 * reads the codestream instead of the `nclx` box, or one that falls back to its own default when the codestream is
 * silent, lands the picture where core says.
 *
 * For a HEIC, every HEVC sequence parameter set in the `hvcC` of the primary item and of the tiles it is derived from,
 * and every one at the start of their item data, has its video signal rewritten. For an AVIF, every AV1 sequence
 * header in their `av1C` and among the OBUs at the start of their item data has its colour config rewritten.
 *
 * With an `nclx`, a unit is rewritten when it is silent or names a primaries, transfer, matrix or range other than the
 * `nclx`, and then states the `nclx` primaries, transfer, matrix and range. A primaries or transfer code point past
 * 255 is written as 2 for unspecified. A chromaticity derived matrix is written as the named matrix whose coefficients
 * H.273 derives from the `nclx` primaries, the [equivalentCode] of its reading, so 12 as 1 on BT.709 primaries and as 9
 * on BT.2020 primaries, and 13 as 10 on BT.2020 primaries. On primaries no named matrix matches it is written as it
 * is. A matrix of 2 for unspecified is written as [UNSPECIFIED_MATRIX_DECODES_AS], the matrix libheif and the decoders
 * that read the codestream use for an unspecified one, so no decoder picks its own. The identity matrix is written as
 * 0, which AV1 allows only for unsubsampled samples.
 *
 * Without an `nclx`, the picture lands with its codestream's own signal. When the first unit leaves its matrix out or
 * names 2 for unspecified, every unit is made to state that unit's primaries, transfer and range with matrix
 * [UNSPECIFIED_MATRIX_DECODES_AS], a primaries or transfer it leaves out as 2 and a range it leaves out as limited. A
 * codestream that names its matrix is left as it is.
 *
 * Every other field of the unit stays bit for bit, and the box sizes and item locations around it move with it.
 * Reading the result back gives a [YcbcrCoding.codestream] that states what was written, while [YcbcrCoding.matrix]
 * stays the `nclx` matrix as the box records it, 2 and 12 included.
 *
 * Returns [bytes] itself when nothing needs rewriting, a file with a `moov` box, and anything not understood, a
 * truncated or malformed file included. So is a picture whose [YcbcrCoding.identityNot444] is true, and an AVIF
 * whose `nclx` names the identity matrix at limited range with BT.709 primaries and the sRGB transfer, which AV1 cannot
 * signal. It never throws.
 */
@InternalFilmstripApi
public fun heifWithExplicitCodestreamColour(bytes: ByteArray): ByteArray = heifWithExplicitColour(bytes)

/**
 * The matrix coefficients, colour primaries and range [heifWithRestatedColour] states in place of the ones a picture
 * lands with.
 *
 * @property matrix The ITU-T H.273 matrix coefficients code point to state.
 * @property primaries The ITU-T H.273 colour primaries code point to state beside [matrix].
 * @property fullRange The range to state, true for full and false for limited, or null to state the range the picture
 * lands with.
 * @property nclx Whether every `colr nclx` the primary item and its tiles carry states [matrix], [primaries] and the
 * range too, for a decoder that reads them from the `nclx` rather than from the codestream.
 */
@InternalFilmstripApi
public data class RestatedColour(
  public val matrix: Int,
  public val primaries: Int,
  public val fullRange: Boolean? = null,
  public val nclx: Boolean = false,
)

/**
 * [bytes] rewritten like [heifWithExplicitCodestreamColour], with every unit stating the matrix, primaries and range
 * [restate] gives in place of the `nclx` or codestream ones, so a decoder that cannot read the picture's own matrix, or
 * reads it only beside certain primaries, is handed a pair it can.
 *
 * [restate] is handed the matrix and full range flag the units would state, and the [YcbcrCoding.reading]
 * [dev.jordond.filmstrip.internal.pictureYcbcrCoding] gives for [bytes] as they are. For a file already rewritten,
 * with a colour description hidden, that is the reading of the file as given and can differ from the original
 * picture's, so a caller wanting the original's reads it from the original bytes. A chromaticity derived matrix whose
 * reading has an [equivalentCode] on the `nclx` or codestream primaries arrives as that code, and an unspecified one
 * as [UNSPECIFIED_MATRIX_DECODES_AS]. Any other code point arrives as it is, 12 and 13 on primaries no named matrix
 * matches included, so [restate] must answer for every code point from 0 to 255.
 * Returning a matrix or primaries code point below 0 or past 255 is a bug in [restate] and throws
 * [IllegalArgumentException].
 *
 * Only the units are restated, and the `nclx` is left as it was unless [RestatedColour.nclx] is set. Then every
 * `colr nclx` associated with the primary item or its tiles states the restated matrix, primaries and range in place,
 * keeping its transfer and every other bit. Without an `nclx`, a codestream that names its matrix is rewritten too,
 * with its own transfer and range, when it does not already state what [restate] gives.
 *
 * Read the picture's [YcbcrCoding] from [bytes], not from the result. Reading the result back gives the restated matrix
 * in [YcbcrCoding.codestream], and in [YcbcrCoding.matrix] too for a picture without an `nclx`.
 *
 * Returns [bytes] itself for a file with a `moov` box, anything not understood and a picture whose
 * [YcbcrCoding.identityNot444] is true, as [heifWithExplicitCodestreamColour] does, and for a picture with an `nclx`
 * matrix past 255, with no sequence parameter set or sequence header at all or with one that does not parse. [restate]
 * is not called for any of those. It is called once for every other picture, and the result can still be [bytes]
 * itself after it answers, when every unit already states its answer and so does every `nclx` [RestatedColour.nclx]
 * asks for, it gives the identity matrix on samples not known to be 4:4:4, a unit cannot state its answer or the
 * rewritten file does not read back. A caller treats the restate as applied only when the result is not [bytes]
 * itself. It never throws unless [restate] does or gives a code point outside 0 to 255.
 *
 * @param restate The matrix and primaries to state in place of an ITU-T H.273 matrix coefficients code point and full
 * range flag, given the reading of [bytes] as well.
 */
@InternalFilmstripApi
public fun heifWithRestatedColour(
  bytes: ByteArray,
  restate: (matrix: Int, fullRange: Boolean, reading: YcbcrReading) -> RestatedColour,
): ByteArray = heifWithExplicitColour(bytes, restate)
