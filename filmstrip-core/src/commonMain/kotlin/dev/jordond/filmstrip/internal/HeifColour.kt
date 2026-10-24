package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Cicp
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.Icc
import dev.jordond.filmstrip.media.YcbcrCoding

// Reads an AVIF or HEIC primary item's colour descriptions and stored channels out of its ISOBMFF `meta` box.

/**
 * The colour descriptions of [file]'s primary item, its `prof` or `rICC` profile and then its `nclx` code points, each
 * followed by the same from the first tile the item is derived from that has one, or null without a primary item.
 *
 * The stored channels come from the item's `pixi`, then its `av1C` or `hvcC`, then the same from its tiles.
 */
internal fun heifColour(file: ByteView): EmbeddedColour? = heifPrimary(file)?.colour()

/**
 * The matrix and range [file]'s primary item lands with, with the codec of the item, the video signal its codestream
 * carries and the bit depth and chroma layout of its samples, or null for a file whose item has no `nclx` and is
 * neither HEVC nor AV1.
 *
 * The matrix, range, primaries and transfer are the first `nclx` [heifColour] reads. Without one they are the
 * codestream's own, a matrix, primaries or transfer it leaves out read as 2 for unspecified and a range it leaves out
 * as limited, the way an HEVC decoder infers it, and a codestream that does not read is taken as silent.
 *
 * For HEVC the signal is the first sequence parameter set's in the item's `hvcC`, or the first at the start of the
 * item's data when the `hvcC` holds none. For AV1 it is the first sequence header's at the start of the item's data,
 * the one a decoder reads, or the first in its `av1C` when the data holds none. A grid takes its first tile's. The
 * signal is null when the unit taken does not parse, or the units ahead of it do not.
 *
 * The depth and layout are what every unit [codestreamUnits] walks in the item and its tiles states, together with the
 * fields of each of their `hvcC` or `av1C` boxes. Both are null when there is no such unit, or when any unit or box
 * does not read or states another depth or layout than the rest.
 */
internal fun heifYcbcrCoding(file: ByteView): YcbcrCoding? {
  val primary = heifPrimary(file) ?: return null
  val nclx = primary.nclx()
  val codec = primary.codec()
  if (nclx == null && codec == CodecKind.Other) return null
  return primary.ycbcrCoding(file, codec, nclx, primary.signal(codec, file))
}

// The coding heifYcbcrCoding gives for an item of codec with nclx and the codestream signal already read. Without an
// nclx the codec is HEVC or AV1.
private fun HeifPrimary.ycbcrCoding(
  file: ByteView,
  codec: CodecKind,
  nclx: Cicp?,
  codestream: CodestreamSignal?,
): YcbcrCoding {
  val samples = samples(codec, file)
  if (nclx != null) {
    return YcbcrCoding(
      nclx.matrix,
      nclx.fullRange,
      codec,
      codestream,
      fromNclx = true,
      bitDepth = samples?.bitDepth,
      chromaFormat = samples?.chromaFormat,
      primaries = nclx.primaries,
      transfer = nclx.transfer,
    )
  }
  return YcbcrCoding(
    codestream?.matrix ?: UNSPECIFIED,
    codestream?.fullRange ?: false,
    codec,
    codestream,
    fromNclx = false,
    bitDepth = samples?.bitDepth,
    chromaFormat = samples?.chromaFormat,
    primaries = codestream?.primaries ?: UNSPECIFIED,
    transfer = codestream?.transfer ?: UNSPECIFIED,
  )
}

/**
 * The coded items of an AVIF or HEIC's primary picture, the primary item and each tile it is derived from, with the
 * colour they are decoded with and where their data is kept.
 *
 * @property codec The codec the items are coded with, [CodecKind.Hevc] or [CodecKind.Av1].
 * @property nclx The code points of the picture's `nclx`, or null when it has none.
 * @property codestream The video signal of the items' codestream as [heifYcbcrCoding] reads it, or null when it does
 * not read.
 * @property items Each item id with the first `hvcC` or `av1C` property associated with it, the one its codec names,
 * the primary item before its tiles.
 * @property locations The item locations in the `iloc` of the `meta` box the items sit in, or null when it has none.
 * @property idat The `idat` box of that `meta` box, or null when it has none.
 * @property coding The matrix, range, depth and layout [heifYcbcrCoding] reads for the picture, read on first use.
 * @property nclxBoxes Every `colr nclx` property the items are associated with, in `ipco` order, read on first use.
 */
internal class CodedItems(
  val codec: CodecKind,
  val nclx: Cicp?,
  val codestream: CodestreamSignal?,
  val items: List<Pair<Long, Box>>,
  val locations: ItemLocations?,
  val idat: Box?,
  coding: () -> YcbcrCoding,
  nclxBoxes: () -> List<Box> = { emptyList() },
) {
  val coding: YcbcrCoding by lazy(coding)
  val nclxBoxes: List<Box> by lazy(nclxBoxes)
}

/**
 * The [CodedItems] of [file], its nclx being the first one [heifYcbcrCoding] reads. Gives null when the file has no
 * primary item or the primary item is neither HEVC nor AV1.
 *
 * Reads the `iloc` once, for [CodedItems.locations] and every item data read behind them, and throws [MalformedPicture]
 * when it does not parse.
 */
internal fun heifCodedItems(file: ByteView): CodedItems? {
  val primary = heifPrimary(file) ?: return null
  val codec = primary.codec().takeIf { it == CodecKind.Hevc || it == CodecKind.Av1 } ?: return null
  val locations = primary.locations(file)
  val signal = primary.signal(codec, file)
  val nclx = primary.nclx()
  val coding = { primary.ycbcrCoding(file, codec, nclx, signal) }
  val configurations = primary.configurations(codec)
  return CodedItems(codec, nclx, signal, configurations, locations, primary.idat, coding, primary::nclxProperties)
}

// Whether [file]'s primary item is derived from tiles, false without a primary item.
internal fun heifTiled(file: ByteView): Boolean = heifPrimary(file)?.tiles?.isNotEmpty() == true

// The primary item of an AVIF or HEIC, or null when its meta box has no primary item or no item properties.
private fun heifPrimary(file: ByteView): HeifPrimary? {
  val meta = file.boxes(0, file.end).firstOrNull { it.type == META } ?: return null
  val heif = file.within(meta.end)
  var pitm: Box? = null
  var iinf: Box? = null
  var iref: Box? = null
  var ipco: Box? = null
  var iloc: Box? = null
  var idat: Box? = null
  val ipmas = mutableListOf<Box>()
  for (box in heif.boxes(meta.start + 4, meta.end)) {
    when (box.type) {
      PITM -> {
        pitm = pitm ?: box
      }
      ILOC -> {
        iloc = iloc ?: box
      }
      IDAT -> {
        idat = idat ?: box
      }
      IINF -> {
        iinf = iinf ?: box
      }
      IREF -> {
        iref = iref ?: box
      }
      IPRP -> {
        for (child in heif.within(box.end).boxes(box.start, box.end)) {
          when (child.type) {
            IPCO -> ipco = ipco ?: child
            IPMA -> ipmas += child
          }
        }
      }
    }
  }
  val primaryBox = pitm ?: return null
  val properties = ipco ?: return null
  if (ipmas.isEmpty()) return null
  val primary = heif.within(primaryBox.end).let { it.itemId(primaryBox.start + 4, it.u8(primaryBox.start)) }
  val listed = heif.boxes(properties.start, properties.end).take(MAX_PROPERTY_INDEX).toList()
  val kinds = heif.propertyKinds(listed)
  val tiles = iref?.let { heif.within(it.end).tilesOf(primary, it) } ?: LongArray(0)
  val found = FirstProperties(tiles)
  for (ipma in ipmas) heif.within(ipma.end).recordProperties(ipma, primary, kinds, found)
  return HeifPrimary(heif, primary, tiles, listed, found, ipmas, iinf, iloc, idat)
}

// An AVIF or HEIC primary item, the tiles it is derived from and the first property of each kind on each of them.
private class HeifPrimary(
  private val heif: ByteView,
  private val primary: Long,
  val tiles: LongArray,
  private val properties: List<Box>,
  private val found: FirstProperties,
  private val ipmas: List<Box>,
  private val iinf: Box?,
  private val iloc: Box?,
  val idat: Box?,
) {
  private var located: ItemLocations? = null

  // The item locations in the iloc of meta, read on first use and kept for later calls, null when meta has no iloc.
  fun locations(file: ByteView): ItemLocations? = located ?: iloc?.let { file.itemLocations(it) }?.also { located = it }

  fun colour(): EmbeddedColour {
    val profile = { index: Int -> property(index)?.let { box -> deferring { heif.profileOf(box) } } }
    val nclx = { index: Int -> property(index)?.let { box -> deferring { heif.nclxOf(box) } } }
    val descriptions =
      listOfNotNull(
        profile(found.primary[PropertyKind.PROFILE]),
        nclx(found.primary[PropertyKind.NCLX]),
        profile(found.firstTile(tiles, PropertyKind.PROFILE)),
        nclx(found.firstTile(tiles, PropertyKind.NCLX)),
      )
    val channels =
      listOf(
        found.primary[PropertyKind.PIXI] to PropertyKind.PIXI,
        found.primary[PropertyKind.CODEC] to PropertyKind.CODEC,
        found.firstTile(tiles, PropertyKind.PIXI) to PropertyKind.PIXI,
        found.firstTile(tiles, PropertyKind.CODEC) to PropertyKind.CODEC,
      ).firstNotNullOfOrNull { (index, kind) ->
        property(index)?.let { box -> deferring { heif.channelsOf(box, kind) } }
      }
    return EmbeddedColour(descriptions, channels)
  }

  fun nclx(): Cicp? = colour().descriptions.firstNotNullOfOrNull { it as? Cicp }

  // Every colr nclx property the item or one of its tiles is associated with, in ipco order.
  fun nclxProperties(): List<Box> =
    associatedProperties().sorted().mapNotNull { property(it) }.filter { box ->
      box.type == COLR && heif.within(box.end).u32(box.start).toInt() == NCLX_TYPE && heif.nclxOf(box) != null
    }

  // The index of every property the ipma boxes associate with the item or one of its tiles.
  fun associatedProperties(): Set<Int> {
    val items = tiles.toHashSet().apply { add(primary) }
    return buildSet {
      for (ipma in ipmas) {
        heif.within(ipma.end).forEachAssociation(ipma) { item, index -> if (item in items) add(index) }
      }
      remove(0)
    }
  }

  // The video signal of the item's codestream, read from the first sequence parameter set in an hvcC for HEVC and from
  // a sequence header for AV1.
  fun signal(
    codec: CodecKind,
    file: ByteView,
  ): CodestreamSignal? =
    when (codec) {
      CodecKind.Hevc -> firstSps(file)?.signal
      CodecKind.Av1 -> sequenceHeader(file)?.signal
      else -> null
    }

  // The codec the item's infe names, hvc1 and hev1 for HEVC and av01 for AV1. An item of any other type that is derived
  // from tiles, such as a grid, takes its first tile's codec.
  fun codec(): CodecKind {
    val types = iinf?.let { deferring { heif.within(it.end).itemTypes(it) } } ?: return CodecKind.Other
    val own = codecOf(types[primary])
    if (own != CodecKind.Other || tiles.isEmpty()) return own
    return codecOf(types[tiles.first()])
  }

  // The first sequence parameter set in the hvcC of the item, or its first tile with one, then the first one at the
  // start of that item's data when the hvcC holds none. Null when that is not an hvcC, or the one taken or the arrays
  // ahead of it do not parse.
  private fun firstSps(file: ByteView): SpsLayout? {
    val (item, index) = codecProperties().firstOrNull { it.second != 0 } ?: return null
    val configuration = deferring { property(index) }?.takeIf { it.type == HVCC } ?: return null
    val hvcc = heif.within(configuration.end)
    hvccSpsLayout(hvcc, configuration.start)?.let { return it }
    val holdsNone =
      deferring {
        hvcc.hvccUnits(configuration.start) { type, _, _ -> if (type == SPS_NAL) return@deferring false }
        true
      }
    if (holdsNone != true) return null
    val inBand =
      deferring { file.codestreamUnits(CodecKind.Hevc, listOf(item to configuration), locations(file), idat) }
    return inBand?.firstOrNull()?.let { spsLayout(unescape(file.bytes.copyOfRange(it.payload, it.end))) }
  }

  // The bit depth and chroma layout every unit and every hvcC or av1C of the item and its tiles states, or null when
  // there is no unit or one of them does not read or states another.
  fun samples(
    codec: CodecKind,
    file: ByteView,
  ): Samples? {
    if (codec != CodecKind.Hevc && codec != CodecKind.Av1) return null
    val items = configurations(codec)
    val units = deferring { file.codestreamUnits(codec, items, locations(file), idat) }
    if (units.isNullOrEmpty()) return null
    val stated = units.map { file.samplesOf(it) ?: return null }
    val configured = items.map { it.second }.distinctBy { it.start }.map { configured(it) ?: return null }
    return (stated + configured).distinct().singleOrNull()
  }

  // The bit depth and chroma layout an av1C or hvcC states, null when it does not read. An hvcC gives both or neither.
  private fun configured(configuration: Box): Samples? {
    val box = heif.within(configuration.end)
    return deferring {
      if (configuration.type == AV1C) {
        av1Samples(box.u8(configuration.start + AV1C_SAMPLES))
      } else {
        Samples(
          (box.u8(configuration.start + HEVC_LUMA_DEPTH) and HEVC_DEPTH_MASK) + EIGHT_BITS,
          box.u8(configuration.start + HEVC_CHROMA_FORMAT) and HEVC_CHROMA_MASK,
        )
      }
    }
  }

  // The first sequence header at the start of the data of the item, or its first tile with an av1C, then the first one
  // in that av1C when the data holds none. Null when the one taken does not parse, the units at the start of the data
  // do not, or neither holds a sequence header.
  private fun sequenceHeader(file: ByteView): SequenceHeaderLayout? {
    val (item, index) = codecProperties().firstOrNull { it.second != 0 } ?: return null
    val configuration = deferring { property(index) }?.takeIf { it.type == AV1C } ?: return null
    val inBand =
      deferring {
        val extent = locations(file)?.let { file.firstExtent(it, idat, item) }
        extent?.let { (start, stop) -> file.sequenceHeaderLayouts(start, stop) }.orEmpty()
      } ?: return null
    if (inBand.isNotEmpty()) return inBand.first()
    return deferring { heif.sequenceHeaderLayouts(configuration.start + 4, configuration.end).firstOrNull() }
  }

  // Each of the item and its tiles with the first hvcC or av1C, whichever codec names, associated with it.
  fun configurations(codec: CodecKind): List<Pair<Long, Box>> {
    val type = if (codec == CodecKind.Hevc) HVCC else AV1C
    return codecProperties().mapNotNull { (item, index) ->
      property(index)?.takeIf { it.type == type }?.let { item to it }
    }
  }

  // The item and each of its tiles beside the index of its own av1C or hvcC, zero for none.
  private fun codecProperties(): List<Pair<Long, Int>> =
    listOf(primary to found.primary[PropertyKind.CODEC]) + tiles.map { it to found.ofTile(it, PropertyKind.CODEC) }

  private fun property(index: Int): Box? = if (index == 0) null else properties.getOrElse(index - 1) { malformed() }
}

// The depth and layout the av1C byte holding high_bitdepth, twelve_bit, monochrome and the subsampling states, null
// for a layout AV1 has no number for.
private fun av1Samples(fields: Int): Samples? {
  val depth = av1BitDepth(fields and AV1C_HIGH_BITDEPTH != 0, fields and AV1C_TWELVE_BIT != 0)
  val x = fields and AV1C_SUBSAMPLING_X != 0
  val y = fields and AV1C_SUBSAMPLING_Y != 0
  return av1ChromaFormat(fields and AV1_MONOCHROME != 0, x, y)?.let { Samples(depth, it) }
}

private fun ByteView.profileOf(colr: Box): Icc? = iccDescription(within(colr.end).profile(colr.start + 4, colr.end))

private fun ByteView.nclxOf(colr: Box): Cicp? {
  val box = within(colr.end)
  val body = colr.start + 4
  val fullRange = box.u8(body + 6) and FULL_RANGE_FLAG != 0
  return Cicp(box.u16(body), box.u16(body + 2), box.u16(body + 4), fullRange)
}

// A pixi box counts the item's channels. An av1C or hvcC names monochrome, one channel, or else colour.
private fun ByteView.channelsOf(
  property: Box,
  kind: Int,
): Int {
  val box = within(property.end)
  return when {
    kind == PropertyKind.PIXI -> box.u8(property.start + 4)
    property.type == AV1C -> if (box.u8(property.start + 2) and AV1_MONOCHROME != 0) 1 else 3
    else -> if (box.u8(property.start + HEVC_CHROMA_FORMAT) and HEVC_CHROMA_MASK == ChromaFormat.MONOCHROME) 1 else 3
  }
}

// The kind of each of the first 32767 ipco properties, the most an ipma entry can address, or PropertyKind.NONE for one
// no description or channel count reads.
private fun ByteView.propertyKinds(properties: List<Box>): ByteArray {
  val kinds = ByteArray(MAX_PROPERTY_INDEX + 1) { PropertyKind.NONE.toByte() }
  for ((offset, property) in properties.withIndex()) {
    val kind =
      when (property.type) {
        COLR -> {
          when (deferring { within(property.end).u32(property.start).toInt() }) {
            PROF, RICC -> PropertyKind.PROFILE
            NCLX_TYPE -> if (deferring { nclxOf(property) } != null) PropertyKind.NCLX else PropertyKind.NONE
            else -> PropertyKind.NONE
          }
        }
        PIXI_TYPE -> {
          PropertyKind.PIXI
        }
        AV1C, HVCC -> {
          PropertyKind.CODEC
        }
        else -> {
          PropertyKind.NONE
        }
      }
    kinds[offset + 1] = kind.toByte()
  }
  return kinds
}

internal val META = fourcc("meta")
private val PITM = fourcc("pitm")
private val IINF = fourcc("iinf")
private val IREF = fourcc("iref")
internal val IPRP = fourcc("iprp")
internal val IPCO = fourcc("ipco")
private val IPMA = fourcc("ipma")
private val COLR = fourcc("colr")
private val PROF = fourcc("prof")
private val RICC = fourcc("rICC")
private val NCLX_TYPE = fourcc("nclx")
private val PIXI_TYPE = fourcc("pixi")
internal val AV1C = fourcc("av1C")
internal val HVCC = fourcc("hvcC")

private const val MAX_PROPERTY_INDEX = 0x7FFF
private const val UNSPECIFIED = 2
private const val FULL_RANGE_FLAG = 0x80
private const val AV1_MONOCHROME = 0x10
private const val HEVC_CHROMA_FORMAT = 16

// The hvcC byte holding bitDepthLumaMinus8 and the bits of each field, and the av1C byte holding high_bitdepth,
// twelve_bit, monochrome and the subsampling, with each of their bits.
private const val HEVC_LUMA_DEPTH = 17
private const val HEVC_CHROMA_MASK = 3
private const val HEVC_DEPTH_MASK = 7
private const val AV1C_SAMPLES = 2
private const val AV1C_HIGH_BITDEPTH = 0x40
private const val AV1C_TWELVE_BIT = 0x20
private const val AV1C_SUBSAMPLING_X = 0x08
private const val AV1C_SUBSAMPLING_Y = 0x04
private const val EIGHT_BITS = 8

/**
 * The chroma sample layouts, numbered the way HEVC's `chroma_format_idc` is. An AV1 layout is given the HEVC number
 * for the same subsampling.
 */
internal object ChromaFormat {
  const val MONOCHROME = 0
  const val YUV420 = 1
  const val YUV422 = 2
  const val YUV444 = 3
}
