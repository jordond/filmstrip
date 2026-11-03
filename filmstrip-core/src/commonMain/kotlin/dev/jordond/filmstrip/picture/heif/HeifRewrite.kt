package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.equivalentCode
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.container
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureYcbcrCoding

// Rewrites the HEVC sequence parameter sets or AV1 sequence headers an AVIF or HEIC's primary item and its tiles carry
// so their color states what the item lands with, then moves every box size and item location the longer or shorter
// units shift.

/**
 * [bytes] with the codestream of an AVIF or HEIC's primary item stating the color it lands with, so a decoder that
 * reads the codestream instead of the `nclx` box, or one that falls back to its own default when the codestream is
 * silent, lands the picture where core says.
 *
 * For a HEIC, every HEVC sequence parameter set in the `hvcC` of the primary item and of the tiles it is derived from,
 * and every one at the start of their item data, has its video signal rewritten. For an AVIF, every AV1 sequence
 * header in their `av1C` and among the OBUs at the start of their item data has its color config rewritten.
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
 * The `irot`, `imir` and `clap` associations of the primary item and its alpha items are dropped from their `ipma`
 * entries as well, the way [heifWithoutOrientation] drops them, so no decoder crops, turns or mirrors the samples and
 * [pictureOrientationOf] on the result reads [PictureOrientation.Normal]. Read the orientation from [bytes], not from
 * the result. They are dropped even when the codestream needs nothing.
 *
 * Gives [UnturnedHeif.Refused] for a file whose drop is refused, as [heifWithoutOrientation] lists, and the
 * codestream is then not stated. Otherwise gives [UnturnedHeif.Dropped] or [UnturnedHeif.Untagged] as the drop does,
 * holding [bytes] itself when the file is untagged and nothing needs rewriting, or anything is not understood, a
 * truncated or malformed file included. A file with a `moov` box keeps its codestream as it is. So does a picture
 * whose [YcbcrCoding.identityNot444] is true, or an AVIF whose `nclx` names the identity matrix at limited range with
 * BT.709 primaries and the sRGB transfer, which AV1 cannot signal. It never throws.
 *
 * Every box holding a rewritten unit grows or shrinks with it, a 64-bit size included, and every `iloc` base offset,
 * extent offset and extent length the change moves is fixed, file offsets for construction method 0 and offsets into
 * `idat` for method 1. The units of a file with a `moov` box are not stated, since its sample tables hold offsets of
 * their own. Bytes after the last top-level box that do not parse as one are carried over as they were.
 *
 * The result is read back before it is given. Its top-level boxes must walk to where the input's end up, every item
 * extent must hold the input's bytes with only the rewritten units changed, and it must carry at least one unit, each
 * one [codestreamUnits] walks stating the signal written. Otherwise the units are left as they were.
 */
@InternalFilmstripApi
public fun heifWithExplicitCodestreamColor(bytes: ByteArray): UnturnedHeif =
  when (val unturned = heifWithoutOrientation(bytes)) {
    is UnturnedHeif.Refused -> unturned
    is UnturnedHeif.Dropped -> UnturnedHeif.Dropped(stated(unturned.bytes, restate = null) ?: unturned.bytes)
    is UnturnedHeif.Untagged -> UnturnedHeif.Untagged(stated(bytes, restate = null) ?: bytes)
  }

/**
 * [bytes] rewritten like [heifWithExplicitCodestreamColor], with every unit stating the matrix, primaries and range
 * [restate] gives in place of the `nclx` or codestream ones, so a decoder that cannot read the picture's own matrix, or
 * reads it only beside certain primaries, is handed a pair it can.
 *
 * [restate] is handed the matrix and full range flag the units would state, and the [YcbcrCoding.reading]
 * [pictureYcbcrCoding] gives for [bytes] as they are. For a file already rewritten, with a color description hidden,
 * that is the reading of the file as given and can differ from the original picture's, so a caller wanting the
 * original's reads it from the original bytes. A chromaticity derived matrix whose reading has an [equivalentCode] on
 * the `nclx` or codestream primaries arrives as that code, and an unspecified one as [UNSPECIFIED_MATRIX_DECODES_AS].
 * Any other code point arrives as it is, 12 and 13 on primaries no named matrix matches included, so [restate] must
 * answer for every code point from 0 to 255. Returning a matrix or primaries code point below 0 or past 255 is a bug in
 * [restate] and throws [IllegalArgumentException].
 *
 * Only the units are restated, and the `nclx` is left as it was unless [RestatedColor.nclx] is set. Then every
 * `colr nclx` associated with the primary item or its tiles states the restated matrix, primaries and range in place,
 * keeping its transfer and every other bit. Without an `nclx`, a codestream that names its matrix is rewritten too,
 * with its own transfer and range, when it does not already state what [restate] gives.
 *
 * Read the picture's [YcbcrCoding] from [bytes], not from the result. Reading the result back gives the restated matrix
 * in [YcbcrCoding.codestream], and in [YcbcrCoding.matrix] too for a picture without an `nclx`.
 *
 * Gives [UnturnedHeif.Refused] for a file whose `irot`, `imir` and `clap` drop is refused, as [heifWithoutOrientation]
 * lists, without calling [restate]. Otherwise every result has the `irot`, `imir` and `clap` dropped the way
 * [heifWithoutOrientation] drops them.
 *
 * Gives [RestatedHeif.Unrestated] when the restate does not apply: for a file with a `moov` box, anything not
 * understood and a picture whose [YcbcrCoding.identityNot444] is true, as [heifWithExplicitCodestreamColor] keeps its
 * codestream, and for a picture with an `nclx` matrix past 255, with no sequence parameter set or sequence header at
 * all or with one that does not parse. [restate] is not called for any of those. It is called once for every other
 * picture, and the result can still be [RestatedHeif.Unrestated] after it answers, when it gives the identity matrix
 * on samples not known to be 4:4:4, a unit cannot state its answer or the rewritten file does not read back. Its
 * bytes are the file with only the drop made, or [bytes] itself when the drop finds the file untagged, so a caller
 * hands them over as they are. Every other picture is [RestatedHeif.Restated], one whose units and every `nclx`
 * [RestatedColor.nclx] asks for already state the answer included. It never throws unless [restate] does or gives a
 * code point outside 0 to 255.
 *
 * @param restate The matrix and primaries to state in place of an ITU-T H.273 matrix coefficients code point and full
 * range flag, given the reading of [bytes] as well.
 */
@InternalFilmstripApi
public fun heifWithRestatedColor(
  bytes: ByteArray,
  restate: (matrix: Int, fullRange: Boolean, reading: YcbcrReading) -> RestatedColor,
): RestatedHeif =
  when (val unturned = heifWithoutOrientation(bytes)) {
    is UnturnedHeif.Refused -> {
      unturned
    }
    is UnturnedHeif.Handed -> {
      stated(unturned.bytes, restate)?.let(RestatedHeif::Restated) ?: RestatedHeif.Unrestated(unturned.bytes)
    }
  }

// The bytes with their units stating the target, the bytes themselves when they already do, or null when they are no
// AVIF or HEIC or on any refusal.
private fun stated(
  bytes: ByteArray,
  restate: ((Int, Boolean, YcbcrReading) -> RestatedColor)?,
): ByteArray? {
  val file = ByteView(bytes, bytes.size)
  if (file.container() != PictureContainer.Heif) return null
  return deferring { rewrite(file, restate) }
}

// The file with its units stating the target, file's own bytes when they already do, or null on any refusal.
private fun rewrite(
  file: ByteView,
  restate: ((Int, Boolean, YcbcrReading) -> RestatedColor)?,
): ByteArray? {
  val coded = heifCodedItems(file) ?: return null
  if (coded.coding.identityNot444) return null
  val stated = coded.stated(restated = restate != null) ?: return null
  val top = file.leadingBoxes()
  if (top.any { it.type == MOOV }) return null
  val locations = coded.locations
  val idat = coded.idat
  val units = file.codestreamUnits(coded.codec, coded.items, locations, idat)
  if (units.isEmpty() || units.any { file.signalOf(it) == null }) return null
  val restated = restate?.let { coded.restated(stated, it) }
  val target = coded.target(stated, restated) ?: return null
  val found = mutableListOf<Splice>()
  for (unit in units) found += file.splice(unit, target.signal()) ?: return null
  if (restated?.nclx == true) found += coded.nclxBoxes.mapNotNull { file.nclxSplice(it, target) }
  val splices = found.distinctBy { it.start }.sortedBy { it.start }
  if (splices.isEmpty()) return file.bytes
  if (splices.zipWithNext().any { (first, second) -> second.start < first.end }) return null
  if (splices.any { splice -> top.none { splice.start >= it.start && splice.end <= it.end } }) return null
  val fields = mutableListOf<Field>()
  file.sizeFields(top, splices, fields)
  if (locations != null) fields += locations.fields(splices, idat) ?: return null
  val rewritten = Rewritten(spliced(file.bytes, fields, splices) ?: return null, splices)
  val intact = rewritten.keepsBoxes(top) && rewritten.keepsItemData(file, locations, idat)
  return rewritten.bytes.takeIf { intact && rewritten.statesTarget(target.signal(), coded.codec) }
}

// Whether every unit the rewrite walks in the primary item and its tiles now reads back as target.
private fun Rewritten.statesTarget(
  target: CodestreamSignal,
  codec: CodecKind,
): Boolean {
  val found = heifCodedItems(file)?.takeIf { it.codec == codec } ?: return false
  val units = file.codestreamUnits(codec, found.items, found.locations, found.idat)
  return units.isNotEmpty() && units.all { file.signalOf(it) == target }
}

// A signal with every field known, the one the units state when nothing is restated.
private data class StatedSignal(
  val primaries: Int,
  val transfer: Int,
  val matrix: Int,
  val fullRange: Boolean,
) {
  fun signal(): CodestreamSignal = CodestreamSignal(primaries, transfer, matrix, fullRange)
}

// The nclx's signal when there is one and otherwise the codestream's own with its matrix made explicit, or null when
// there is nothing to state.
private fun CodedItems.stated(restated: Boolean): StatedSignal? =
  if (nclx != null) nclx.stated() else codestream?.explicit(restated)

// What restate gives for the stated matrix and range and the picture's reading, its code points checked.
private fun CodedItems.restated(
  stated: StatedSignal,
  restate: (Int, Boolean, YcbcrReading) -> RestatedColor,
): RestatedColor {
  val restated = restate(stated.matrix, stated.fullRange, coding.reading)
  require(restated.matrix in 0..MAX_CODE_POINT) { "Restated matrix ${restated.matrix} is not a code point" }
  require(restated.primaries in 0..MAX_CODE_POINT) { "Restated primaries ${restated.primaries} is not a code point" }
  return restated
}

// The signal every unit states, stated with the matrix, primaries and range restated gives in place of its own. Null
// when the restated matrix is identity on samples not 4:4:4.
private fun CodedItems.target(
  stated: StatedSignal,
  restated: RestatedColor?,
): StatedSignal? {
  if (restated == null) return stated
  if (coding.copy(matrix = restated.matrix).identityNot444) return null
  val fullRange = restated.fullRange ?: stated.fullRange
  return stated.copy(primaries = restated.primaries, matrix = restated.matrix, fullRange = fullRange)
}

// The splice that makes the colr nclx box state the primaries, matrix and range of target, its transfer and the
// reserved bits beside the range flag kept, or null when the box already states them.
private fun ByteView.nclxSplice(
  colr: Box,
  target: StatedSignal,
): Splice? {
  val box = within(colr.end)
  val body = colr.start + NCLX_BODY
  val transfer = box.u16(body + 2)
  val range = if (target.fullRange) NCLX_FULL_RANGE else 0
  val flags = (box.u8(body + NCLX_FLAGS) and NCLX_FULL_RANGE.inv()) or range
  val fields = intArrayOf(target.primaries, transfer, target.matrix)
  val replacement = ByteArray(NCLX_FLAGS + 1)
  fields.forEachIndexed { index, value ->
    replacement[2 * index] = (value shr 8).toByte()
    replacement[2 * index + 1] = value.toByte()
  }
  replacement[NCLX_FLAGS] = flags.toByte()
  if (box.matches(body, replacement)) return null
  return Splice(body, body + replacement.size, replacement)
}

// The codestream's own signal with a matrix it leaves out or leaves unspecified made explicit, and any other field it
// leaves out written as unspecified or limited. One that names its matrix gives null unless it is to be restated, and
// is then restated with a chromaticity derived matrix resolved for its own primaries.
private fun CodestreamSignal.explicit(restated: Boolean): StatedSignal? {
  val stated = primaries ?: UNSPECIFIED
  val named = matrix?.takeIf { it != UNSPECIFIED }
  if (named != null && !restated) return null
  return StatedSignal(
    stated,
    transfer ?: UNSPECIFIED,
    named?.let { resolvedMatrix(it, stated, transfer ?: UNSPECIFIED) } ?: UNSPECIFIED_MATRIX_DECODES_AS,
    fullRange ?: false,
  )
}

/**
 * The named matrix coefficients code point a chromaticity derived [matrix] reads the same as beside [primaries] and
 * [transfer], or [matrix] itself for any other code point and for a derived one no named matrix matches.
 */
internal fun resolvedMatrix(
  matrix: Int,
  primaries: Int,
  transfer: Int,
): Int =
  if (matrix == CHROMATICITY_DERIVED || matrix == CHROMATICITY_DERIVED_CONSTANT) {
    ycbcrReading(matrix, primaries, transfer).equivalentCode() ?: matrix
  } else {
    matrix
  }

private fun Cicp.stated(): StatedSignal? {
  if (matrix !in 0..MAX_CODE_POINT) return null
  val named = resolvedMatrix(matrix, primaries, transfer)
  return StatedSignal(
    primaries.takeIf { it in 0..MAX_CODE_POINT } ?: UNSPECIFIED,
    transfer.takeIf { it in 0..MAX_CODE_POINT } ?: UNSPECIFIED,
    if (named == UNSPECIFIED) UNSPECIFIED_MATRIX_DECODES_AS else named,
    fullRange,
  )
}

// The splice that makes unit state target, an empty list when it already does, or null when it does not parse or its
// new length does not fit.
private fun ByteView.splice(
  unit: CodestreamUnit,
  target: CodestreamSignal,
): List<Splice>? {
  val obu = unit.obu ?: return rewrittenUnit(unit.start, unit.end, unit.payload - unit.start, target)
  val payload = bytes.copyOfRange(obu.payload, obu.end)
  val rewritten = sequenceHeaderStating(payload, target) ?: return null
  if (rewritten === payload) return emptyList()
  return listOf(Splice(obu.start, obu.end, (obuHeader(obu, rewritten.size) ?: return null) + rewritten))
}

// The NAL unit behind a length field of lengthBytes at at, rewritten with its new length.
private fun ByteView.rewrittenUnit(
  at: Int,
  end: Int,
  lengthBytes: Int,
  target: CodestreamSignal,
): List<Splice>? {
  val nal = bytes.copyOfRange(at + lengthBytes, end)
  val rewritten = spsStating(nal, target) ?: return null
  if (rewritten === nal) return emptyList()
  if (rewritten.size.toLong() ushr (lengthBytes * 8) != 0L) return null
  val length = ByteArray(lengthBytes) { (rewritten.size ushr (8 * (lengthBytes - 1 - it))).toByte() }
  return listOf(Splice(at, end, length + rewritten))
}

private const val MAX_CODE_POINT = 255
private const val UNSPECIFIED = 2
private const val CHROMATICITY_DERIVED = 12
private const val CHROMATICITY_DERIVED_CONSTANT = 13

// Where the code points start in a colr nclx box past its color type, and where the range flag sits past them.
private const val NCLX_BODY = 4
private const val NCLX_FLAGS = 6
private const val NCLX_FULL_RANGE = 0x80
