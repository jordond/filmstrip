package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind

/**
 * The alpha item an AVIF or HEIC carries for its primary item, as [heifAlpha] reads it.
 *
 * @property item The alpha item's id, which ffmpeg also gives the stream it reads the item as.
 * @property grid How the alpha item is laid out from tiles, or null when it is coded as one image.
 * @property premultiplied Whether the primary item's colour is stored multiplied by this alpha, as an `iref prem` from
 * the primary item to the alpha item records.
 * @property fullRange Whether the alpha item's codestream states full range. A limited range alpha codes clear at 16
 * and opaque at 235, shifted up by the bits past eight.
 * @property bitDepth How deep the alpha item's codestream stores its samples.
 */
@InternalFilmstripApi
public class HeifAlpha(
  public val item: Long,
  public val grid: HeifGrid?,
  public val premultiplied: Boolean,
  public val fullRange: Boolean,
  public val bitDepth: Int,
)

/**
 * A `grid` item's layout, the tiles it is derived from placed in rows and the whole cropped to [size].
 *
 * @property tiles The ids of the tiles, in rows, as the grid's `dimg` reference lists them.
 * @property columns How many tiles each row holds.
 * @property rows How many rows of tiles the grid holds.
 * @property size The width and height of the assembled picture.
 */
@InternalFilmstripApi
public class HeifGrid(
  public val tiles: List<Long>,
  public val columns: Int,
  public val rows: Int,
  public val size: Size,
)

/**
 * The alpha item [bytes] carry for their primary item, or null when they are not an AVIF or HEIC or carry none.
 *
 * An alpha item is one whose `iref auxl` points at the primary item and whose `auxC` names the AVIF or the HEVC alpha
 * auxiliary type. The first one the `iref` lists is taken. A file that is truncated or malformed carries none.
 *
 * The range and depth are the alpha item's own, read from its codestream rather than any `nclx`. For HEVC that is the
 * first sequence parameter set in its `hvcC`, or the first at the start of its data when the `hvcC` holds none, with a
 * range it leaves out read as limited. For AV1 it is the first sequence header at the start of its data, or the first
 * in its `av1C` when the data holds none. A grid takes its first tile's. An alpha item whose codestream does not read
 * carries none either.
 */
@InternalFilmstripApi
public fun heifAlpha(bytes: ByteArray): HeifAlpha? {
  val picture = ByteView(bytes, bytes.size)
  if (!picture.matches(4, ascii("ftyp"))) return null
  return deferring { picture.alpha() }
}

private fun ByteView.alpha(): HeifAlpha? {
  val meta = boxes(0, end).firstOrNull { it.type == META } ?: return null
  val heif = within(meta.end)
  val children = heif.boxes(meta.start + 4, meta.end).toList()
  val pitm = children.firstOrNull { it.type == PITM } ?: return null
  val iref = children.firstOrNull { it.type == IREF } ?: return null
  val iprp = children.firstOrNull { it.type == IPRP } ?: return null
  val primary = heif.within(pitm.end).let { it.itemId(pitm.start + 4, it.u8(pitm.start)) }
  val references = heif.within(iref.end).references(iref).toList()

  val properties = heif.within(iprp.end).boxes(iprp.start, iprp.end).toList()
  val ipco = properties.firstOrNull { it.type == IPCO } ?: return null
  val listed = heif.boxes(ipco.start, ipco.end).take(MAX_PROPERTY_INDEX).toList()
  val ipmas = properties.filter { it.type == IPMA }
  val alphaTyped = mutableSetOf<Long>()
  for (ipma in ipmas) {
    heif.within(ipma.end).forEachAssociation(ipma) { item, index ->
      if (listed.getOrNull(index - 1)?.let { heif.isAlphaType(it) } == true) alphaTyped += item
    }
  }
  val alpha =
    references
      .filter { it.type == AUXL && primary in it.to }
      .map { it.from }
      .firstOrNull { it in alphaTyped } ?: return null

  val premultiplied = references.any { it.type == PREM && it.from == primary && alpha in it.to }
  val types = children.firstOrNull { it.type == IINF }?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  val grid = if (types[alpha] == GRID) grid(alpha, iref, children) ?: return null else null
  val coded = grid?.tiles?.first() ?: alpha
  val configuration = heif.configurationOf(coded, codecOf(types[coded]), ipmas, listed) ?: return null
  val signal = codestream(coded, configuration, children) ?: return null
  return HeifAlpha(alpha, grid, premultiplied, signal.fullRange, signal.bitDepth)
}

// The first av1C or hvcC, whichever [codec] names, that the ipma boxes associate with [item], or null for none.
private fun ByteView.configurationOf(
  item: Long,
  codec: CodecKind,
  ipmas: List<Box>,
  listed: List<Box>,
): Box? {
  val type =
    when (codec) {
      CodecKind.Av1 -> AV1C
      CodecKind.Hevc -> HVCC
      else -> return null
    }
  for (ipma in ipmas) {
    within(ipma.end).forEachAssociation(ipma) { associated, index ->
      val property = listed.getOrNull(index - 1)
      if (associated == item && property?.type == type) return property
    }
  }
  return null
}

// The range and depth of [item]'s codestream, from the unit heifAlpha takes among those its [configuration] and the
// start of its data carry, or null when there is none or it does not read.
private fun ByteView.codestream(
  item: Long,
  configuration: Box,
  children: List<Box>,
): AlphaSignal? {
  val codec = if (configuration.type == AV1C) CodecKind.Av1 else CodecKind.Hevc
  val coded = listOf(item to configuration)
  val configured = codestreamUnits(codec, coded, null, null)
  val locations = children.firstOrNull { it.type == ILOC }?.let { itemLocations(it) }
  val all = codestreamUnits(codec, coded, locations, children.firstOrNull { it.type == IDAT })
  val inBand = all.drop(configured.size)
  val unit = (if (codec == CodecKind.Av1) inBand + configured else configured + inBand).firstOrNull() ?: return null
  val depth = samplesOf(unit)?.bitDepth ?: return null
  val signal = signalOf(unit) ?: return null
  return AlphaSignal(signal.fullRange == true, depth)
}

private class AlphaSignal(
  val fullRange: Boolean,
  val bitDepth: Int,
)

// The layout of grid item [item], its tiles from the dimg reference in [iref] and its rows, columns and size from its
// data, or null when the data is not where iloc places it or the tile count does not match.
private fun ByteView.grid(
  item: Long,
  iref: Box,
  children: List<Box>,
): HeifGrid? {
  val tiles = within(iref.end).tilesOf(item, iref) ?: return null
  val iloc = children.firstOrNull { it.type == ILOC } ?: return null
  val idat = children.firstOrNull { it.type == IDAT }
  val (start, stop) = firstExtent(itemLocations(iloc), idat, item) ?: return null
  val data = within(stop)
  val wide = data.u8(start + 1) and 1 == 1
  val rows = data.u8(start + 2) + 1
  val columns = data.u8(start + 3) + 1
  val width = if (wide) data.u32(start + 4) else data.u16(start + 4).toLong()
  val height = if (wide) data.u32(start + 8) else data.u16(start + 6).toLong()
  if (tiles.size != rows * columns || width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
  return HeifGrid(tiles.toList(), columns, rows, Size(width.toInt(), height.toInt()))
}

// Whether [property] is an auxC naming the AVIF or the HEVC alpha auxiliary type.
private fun ByteView.isAlphaType(property: Box): Boolean {
  if (property.type != AUXC) return false
  val urn = within(property.end)
  val start = property.start + 4
  return ALPHA_TYPES.any { type ->
    urn.matches(start, type) && (start + type.size == urn.end || urn.u8(start + type.size) == 0)
  }
}

private val PITM = fourcc("pitm")
private val IINF = fourcc("iinf")
private val IREF = fourcc("iref")
private val IPMA = fourcc("ipma")
private val AUXL = fourcc("auxl")
private val AUXC = fourcc("auxC")
private val PREM = fourcc("prem")
private val GRID = fourcc("grid")
private val ALPHA_TYPES =
  listOf(ascii("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"), ascii("urn:mpeg:hevc:2015:auxid:1"))
private const val MAX_PROPERTY_INDEX = 0x7FFF
