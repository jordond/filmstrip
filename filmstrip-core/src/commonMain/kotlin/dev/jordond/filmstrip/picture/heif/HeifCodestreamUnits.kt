package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.ChromaFormat
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.malformed

// Walks the HEVC sequence parameter sets or AV1 sequence headers an AVIF or HEIC's coded items carry, in their hvcC or
// av1C and at the start of their item data, and reads what each one states.

/**
 * A sequence parameter set or sequence header carried by a coded item of an AVIF or HEIC.
 *
 * For HEVC the unit starts at the length field ahead of its NAL unit. For AV1 it starts at the OBU header and [obu] is
 * the OBU itself.
 *
 * @property start Where the unit starts, at the length ahead of the NAL unit or at the OBU header.
 * @property payload Where the NAL unit or the OBU payload starts.
 * @property end Where the unit ends.
 * @property obu The sequence header OBU for AV1, or null for HEVC.
 */
internal class CodestreamUnit(
  val start: Int,
  val payload: Int,
  val end: Int,
  val obu: Obu?,
)

/**
 * Every sequence parameter set or sequence header [items] carry, coded with [codec], in the order a rewrite meets them.
 *
 * First come the units of each distinct `hvcC` or `av1C`, every sequence parameter set in its arrays or every sequence
 * header among its configOBUs. Then for each item that [locations] places in the file or [idat], the units at the start
 * of its first extent. For HEVC those are the length prefixed parameter sets ahead of the first coded slice, each
 * length as wide as the item's `hvcC` gives. For AV1 they are the sequence headers among the [leadingObus]. An item
 * whose data sits in another file or is built by another construction method adds none.
 *
 * Throws [MalformedPicture] when a box, array, length or OBU runs past its end, when an `hvcC` gives a three byte
 * length, or when an AV1 item of more than one extent leads with an OBU without a size field, which would run on into
 * the next extent.
 *
 * @param items Each item with its `hvcC` or `av1C`, as [CodedItems.items] lists them.
 * @param locations The item locations of the meta box holding [items], or null when it has no `iloc`.
 * @param idat The `idat` box of that meta box, or null when it has none.
 */
internal fun ByteView.codestreamUnits(
  codec: CodecKind,
  items: List<Pair<Long, Box>>,
  locations: ItemLocations?,
  idat: Box?,
): List<CodestreamUnit> {
  val av1 = codec == CodecKind.Av1
  val units = mutableListOf<CodestreamUnit>()
  for (configuration in items.map { it.second }.distinctBy { it.start }) {
    units += if (av1) configuredHeaders(configuration) else configuredSps(configuration)
  }
  if (locations == null) return units
  for ((item, configuration) in items.distinctBy { it.first }) {
    val location = locations.location(item) ?: continue
    if (location.external || location.method > 1) continue
    val extent = location.extents.firstOrNull() ?: continue
    val (start, stop) = extentBytes(location, extent, idat)
    units += if (av1) inBandHeaders(start, stop, location.extents.size > 1) else inBandSps(start, stop, configuration)
  }
  return units
}

/**
 * The video signal [unit] states, or null when it does not parse.
 */
internal fun ByteView.signalOf(unit: CodestreamUnit): CodestreamSignal? {
  val payload = bytes.copyOfRange(unit.payload, unit.end)
  return if (unit.obu == null) spsLayout(unescape(payload))?.signal else sequenceHeaderLayout(payload)?.signal
}

/**
 * The bit depth and chroma layout [unit] states, or null when it does not parse or names a layout AV1 has no number
 * for.
 */
internal fun ByteView.samplesOf(unit: CodestreamUnit): Samples? {
  val payload = bytes.copyOfRange(unit.payload, unit.end)
  if (unit.obu == null) return spsLayout(unescape(payload))?.let { Samples(it.bitDepth, it.chromaFormat) }
  val layout = sequenceHeaderLayout(payload) ?: return null
  return layout.chromaFormat?.let { Samples(layout.bitDepth, it) }
}

/**
 * The bit depth and chroma layout of an item's samples, the layout numbered as [ChromaFormat] is.
 *
 * @property bitDepth The luma bit depth.
 * @property chromaFormat How the chroma samples are laid out.
 */
internal data class Samples(
  val bitDepth: Int,
  val chromaFormat: Int,
)

private fun ByteView.configuredSps(hvcc: Box): List<CodestreamUnit> {
  val units = mutableListOf<CodestreamUnit>()
  within(hvcc.end).hvccUnits(hvcc.start) { type, unit, end ->
    if (type == SPS_NAL) units += CodestreamUnit(unit - HVCC_LENGTH_BYTES, unit, end, null)
  }
  return units
}

private fun ByteView.configuredHeaders(av1c: Box): List<CodestreamUnit> =
  within(av1c.end).leadingObus(av1c.start + AV1C_FIELDS, av1c.end).sequenceHeaders()

// The parameter sets among the length prefixed units from start up to stop, which stop at the first coded slice.
private fun ByteView.inBandSps(
  start: Int,
  stop: Int,
  hvcc: Box,
): List<CodestreamUnit> {
  val lengthBytes = (within(hvcc.end).u8(hvcc.start + LENGTH_SIZE_AT) and 3) + 1
  if (lengthBytes == 3) malformed()
  val data = within(stop)
  val units = mutableListOf<CodestreamUnit>()
  var pos = start
  while (stop - pos >= lengthBytes + 2) {
    val length = data.sized(pos, lengthBytes)
    if (length < 2 || length > stop - pos - lengthBytes) break
    val unit = pos + lengthBytes
    val type = data.u8(unit) ushr 1 and NAL_TYPE_MASK
    if (type < FIRST_NON_VCL) break
    val unitEnd = unit + length.toInt()
    if (type == SPS_NAL) units += CodestreamUnit(pos, unit, unitEnd, null)
    pos = unitEnd
  }
  return units
}

private fun ByteView.inBandHeaders(
  start: Int,
  stop: Int,
  split: Boolean,
): List<CodestreamUnit> {
  val obus = within(stop).leadingObus(start, stop)
  if (split && obus.any { it.sizeBytes == 0 }) malformed()
  return obus.sequenceHeaders()
}

private fun List<Obu>.sequenceHeaders(): List<CodestreamUnit> =
  filter { it.type == SEQUENCE_HEADER }.map { CodestreamUnit(it.start, it.payload, it.end, it) }

// Where lengthSizeMinusOne sits among the hvcC fields, and the width of the length ahead of each of its units.
private const val LENGTH_SIZE_AT = 21
private const val HVCC_LENGTH_BYTES = 2
private const val FIRST_NON_VCL = 32

// The av1C fields ahead of its configOBUs.
private const val AV1C_FIELDS = 4
