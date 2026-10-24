package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.equivalentCode
import dev.jordond.filmstrip.media.ycbcrReading

// Rewrites the HEVC sequence parameter sets or AV1 sequence headers an AVIF or HEIC's primary item and its tiles carry
// so their colour states what the item lands with, then moves every box size and item location the longer or shorter
// units shift.

/**
 * [bytes] with the codestream of its primary item and tiles stating the primaries, transfer, matrix and range the item
 * lands with, or [bytes] itself when nothing needs it, the file is not an AVIF or HEIC, or any part of it is not
 * understood.
 *
 * For a HEIC each sequence parameter set in the `hvcC` of the items, and each one at the start of their item data, is
 * rewritten by [spsStating]. For an AVIF each sequence header OBU in the `av1C` of the items, and each one among the
 * OBUs at the start of their item data, is rewritten by [sequenceHeaderStating] with its `obu_size` written again. A
 * picture landing with the identity matrix has it stated as 0 on 4:4:4 samples, and is given back as it was when
 * [heifYcbcrCoding] reads it as [dev.jordond.filmstrip.media.YcbcrCoding.identityNot444] or any unit cannot state it. A
 * unit already stating all four fields is left alone.
 *
 * With an `nclx`, the one [heifYcbcrCoding] reads, the units state it. A primaries or transfer past 255 is written as 2
 * for unspecified, a chromaticity derived matrix as [resolvedMatrix] resolves it for the `nclx` primaries and
 * transfer, and a matrix of 2 for unspecified as 6 for BT.601. Without an `nclx`, a codestream whose first unit leaves
 * its matrix out or names 2 has every unit state that unit's own primaries, transfer and range with matrix 6, a field
 * it leaves out written as 2, or limited for the range. One that names its matrix is given back as it was.
 *
 * With [restate], every unit states the matrix, primaries and range it gives for the matrix and range being written and
 * the [dev.jordond.filmstrip.media.YcbcrCoding.reading] of [bytes] as given, in place of the `nclx` or codestream ones.
 * The `nclx` is left as it was unless [RestatedColour.nclx] is set, and then every `colr nclx` in
 * [CodedItems.nclxBoxes] states the same matrix, primaries and range in place, its transfer and reserved bits kept.
 * Without an `nclx`, a codestream that names its matrix is then stated too, with its own transfer and range and a
 * chromaticity derived matrix as [resolvedMatrix] resolves it for the codestream's own primaries and transfer. A matrix
 * or primaries outside 0 to 255 from [restate] throws [IllegalArgumentException].
 *
 * [restate] is called only once the file is known to be an AVIF or HEIC without a `moov` box, whose colour reads with
 * no `nclx` matrix past 255, whose matrix is not identity on samples other than 4:4:4 and which has at least one unit,
 * each parsing. Past that a refusal can depend on its answer, every unit already stating it, a restated identity matrix
 * on samples other than 4:4:4, a unit that cannot state it, a field that no longer fits or a result that does not read
 * back. Each of those gives [bytes] back too, so a caller treats a restate as applied only when the result is not
 * [bytes] itself.
 *
 * Every box holding a rewritten unit grows or shrinks with it, a 64-bit size included, and every `iloc` base offset,
 * extent offset and extent length the change moves is fixed, file offsets for construction method 0 and offsets into
 * `idat` for method 1. A file with a `moov` box is not understood, since its sample tables hold offsets of their own.
 * Bytes after the last top-level box that do not parse as one are carried over as they were.
 *
 * The result is read back before it is given. Its top-level boxes must walk to where the input's end up, every item
 * extent must hold the input's bytes with only the rewritten units changed, and it must carry at least one unit, each
 * one [codestreamUnits] walks stating the signal written. Otherwise [bytes] is given instead.
 *
 * @param restate The matrix and primaries to state in place of a matrix coefficients code point and full range flag,
 * or null to keep the `nclx` or codestream ones.
 */
internal fun heifWithExplicitColour(
  bytes: ByteArray,
  restate: ((matrix: Int, fullRange: Boolean, reading: YcbcrReading) -> RestatedColour)? = null,
): ByteArray {
  val file = ByteView(bytes, bytes.size)
  if (!file.matches(4, FTYP)) return bytes
  return deferring { rewrite(file, restate) } ?: bytes
}

// Replaces the bytes from start up to end with replacement.
private class Splice(
  val start: Int,
  val end: Int,
  val replacement: ByteArray,
) {
  val delta: Int get() = replacement.size - (end - start)
}

// A big endian field of width bytes at a position in the original file, given a new value.
private class Field(
  val at: Int,
  val width: Int,
  val value: Long,
)

private fun rewrite(
  file: ByteView,
  restate: ((Int, Boolean, YcbcrReading) -> RestatedColour)?,
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
  if (splices.isEmpty() || splices.zipWithNext().any { (first, second) -> second.start < first.end }) return null
  if (splices.any { splice -> top.none { splice.start >= it.start && splice.end <= it.end } }) return null
  val fields = mutableListOf<Field>()
  file.sizeFields(top, splices, fields)
  if (locations != null) fields += locations.fields(splices, idat) ?: return null
  val rewritten = Rewritten(spliced(file.bytes, fields, splices) ?: return null, splices)
  val intact = rewritten.keepsBoxes(top) && rewritten.keepsItemData(file, locations, idat)
  return rewritten.bytes.takeIf { intact && rewritten.statesTarget(target.signal(), coded.codec) }
}

// The top-level boxes up to the first one that does not parse, past which a reader ignores the file.
private fun ByteView.leadingBoxes(): List<Box> {
  val found = mutableListOf<Box>()
  val walk = boxes(0, end).iterator()
  while (true) found += deferring { if (walk.hasNext()) walk.next() else null } ?: break
  return found
}

// A file with its splices made, checked against the file it came from before it is handed back.
private class Rewritten(
  val bytes: ByteArray,
  val splices: List<Splice>,
) {
  val file = ByteView(bytes, bytes.size)

  // The coded items of the file, with the item locations and idat of its meta box.
  private val coded by lazy { heifCodedItems(file) }

  // Where a byte at position in the input sits once the splices before it are made.
  fun moved(position: Int): Int = position + splices.filter { it.end <= position }.sumOf { it.delta }

  // Whether the top-level boxes walk exactly to where the input's end up, of the same types in the same order.
  fun keepsBoxes(top: List<Box>): Boolean {
    val covered = moved(top.last().end)
    val boxes = file.within(covered).boxes(0, covered).toList()
    return boxes.map { it.type } == top.map { it.type } && boxes.last().end == covered
  }

  // Whether every item lists the extents it did and each holds the input's bytes with the splices inside it made.
  fun keepsItemData(
    input: ByteView,
    locations: ItemLocations?,
    idat: Box?,
  ): Boolean {
    if (locations == null) return true
    val now = coded?.locations ?: return false
    val nowIdat = coded?.idat
    if (now.items.size != locations.items.size) return false
    return locations.items.zip(now.items).all { (before, after) ->
      val same =
        before.item == after.item && before.method == after.method && before.external == after.external &&
          before.extents.size == after.extents.size
      same && (before.external || before.method > 1 || keepsExtents(input, before, idat, after, nowIdat))
    }
  }

  private fun keepsExtents(
    input: ByteView,
    before: ItemLocation,
    idat: Box?,
    after: ItemLocation,
    nowIdat: Box?,
  ): Boolean =
    before.extents.indices.all { index ->
      val (from, to) = input.extentBytes(before, before.extents[index], idat)
      val (nowFrom, nowTo) = file.extentBytes(after, after.extents[index], nowIdat)
      val expected = input.bytes.splicing(from, to, splices)
      nowTo - nowFrom == expected.size && expected.indices.all { bytes[nowFrom + it] == expected[it] }
    }

  // Whether every unit the rewrite walks in the primary item and its tiles now reads back as target.
  fun statesTarget(
    target: CodestreamSignal,
    codec: CodecKind,
  ): Boolean {
    val found = coded?.takeIf { it.codec == codec } ?: return false
    val units = file.codestreamUnits(codec, found.items, found.locations, found.idat)
    return units.isNotEmpty() && units.all { file.signalOf(it) == target }
  }
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
  restate: (Int, Boolean, YcbcrReading) -> RestatedColour,
): RestatedColour {
  val restated = restate(stated.matrix, stated.fullRange, coding.reading)
  require(restated.matrix in 0..MAX_CODE_POINT) { "Restated matrix ${restated.matrix} is not a code point" }
  require(restated.primaries in 0..MAX_CODE_POINT) { "Restated primaries ${restated.primaries} is not a code point" }
  return restated
}

// The signal every unit states, stated with the matrix, primaries and range restated gives in place of its own. Null
// when the restated matrix is identity on samples not 4:4:4.
private fun CodedItems.target(
  stated: StatedSignal,
  restated: RestatedColour?,
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

// Grows or shrinks each box holding a splice by the splices inside it, descending into meta, iprp and ipco.
private fun ByteView.sizeFields(
  boxes: List<Box>,
  splices: List<Splice>,
  fields: MutableList<Field>,
) {
  for (box in boxes) {
    val inside = splices.filter { it.start >= box.start && it.end <= box.end }
    if (inside.isEmpty()) continue
    val delta = inside.sumOf { it.delta.toLong() }
    when (val size = u32(box.at)) {
      0L -> Unit
      1L -> fields += Field(box.at + 8, 8, u64(box.at + 8) + delta)
      else -> fields += Field(box.at, 4, size + delta)
    }
    val childStart =
      when (box.type) {
        META -> box.start + 4
        IPRP, IPCO -> box.start
        else -> continue
      }
    within(box.end).sizeFields(boxes(childStart, box.end).toList(), inside, fields)
  }
}

// The new base offsets, extent offsets and extent lengths of every item whose data the splices move or resize.
private fun ItemLocations.fields(
  splices: List<Splice>,
  idat: Box?,
): List<Field>? {
  fun moved(position: Long): Long = position + splices.filter { it.end <= position }.sumOf { it.delta.toLong() }

  fun cut(position: Long): Boolean = splices.any { it.start < position && position < it.end }
  val fields = mutableListOf<Field>()
  for (location in items) {
    if (location.external || location.method > 1) continue
    val origin = if (location.method == 0) 0L else (idat ?: return null).start.toLong()
    val start = origin + location.base
    val base = moved(start) - moved(origin)
    if (base != location.base) fields += Field(location.baseAt, baseSize, base)
    for (extent in location.extents) {
      val from = start + extent.offset
      val to = from + extent.length
      if (cut(from) || (extent.length != 0L && cut(to))) return null
      val offset = moved(from) - moved(start)
      val length = if (extent.length == 0L) 0L else moved(to) - moved(from)
      if (offset != extent.offset) fields += Field(extent.offsetAt, offsetSize, offset)
      if (length != extent.length) fields += Field(extent.lengthAt, lengthSize, length)
    }
  }
  return fields
}

// The file with every field written and every splice made, or null when a field does not fit its width.
private fun spliced(
  bytes: ByteArray,
  fields: List<Field>,
  splices: List<Splice>,
): ByteArray? {
  val patched = bytes.copyOf()
  for (field in fields) {
    val fits = field.value >= 0 && (field.width == 8 || (field.width == 4 && field.value <= MAX_U32))
    if (!fits || splices.any { field.at < it.end && field.at + field.width > it.start }) return null
    repeat(field.width) { patched[field.at + it] = (field.value ushr (8 * (field.width - 1 - it))).toByte() }
  }
  return patched.splicing(0, patched.size, splices)
}

// The bytes from from up to to with each splice that sits inside them made.
private fun ByteArray.splicing(
  from: Int,
  to: Int,
  splices: List<Splice>,
): ByteArray {
  val inside = splices.filter { it.start >= from && it.end <= to }
  val out = ByteArray(to - from + inside.sumOf { it.delta })
  var read = from
  var written = 0
  for (splice in inside) {
    copyInto(out, written, read, splice.start)
    written += splice.start - read
    splice.replacement.copyInto(out, written)
    written += splice.replacement.size
    read = splice.end
  }
  copyInto(out, written, read, to)
  return out
}

private val FTYP = ascii("ftyp")
private val MOOV = fourcc("moov")

private const val MAX_CODE_POINT = 255
private const val UNSPECIFIED = 2
private const val CHROMATICITY_DERIVED = 12
private const val CHROMATICITY_DERIVED_CONSTANT = 13
private const val MAX_U32 = 0xFFFFFFFFL

// Where the code points start in a colr nclx box past its colour type, and where the range flag sits past them.
private const val NCLX_BODY = 4
private const val NCLX_FLAGS = 6
private const val NCLX_FULL_RANGE = 0x80
