package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.container

/**
 * The alpha item an AVIF or HEIC carries for its primary item, as [heifAlpha] reads it.
 *
 * @property item The alpha item's id, which [heifCodedImages] reads its sample and configuration by.
 * @property grid How the alpha item is laid out from tiles, or null when it is not a `grid` or is refused.
 * @property premultiplied Whether the primary item's color is stored multiplied by this alpha, as an `iref prem` from
 * the primary item to the alpha item records.
 * @property fullRange Whether the alpha item's codestream states full range. A limited range alpha codes clear at 16
 * and opaque at 235, shifted up by the bits past eight.
 * @property bitDepth How deep the alpha item's codestream stores its samples.
 * @property overlay How the alpha item is drawn from images over a canvas, or null when it is not an `iovl` or is
 * refused.
 * @property refusal Why the alpha item cannot be put together, or null when it can. A `grid` or `iovl` alpha item past
 * one of the bounds [heifPrimaryGrid] or [heifPrimaryOverlay] checks carries the reason it gives.
 * @property reading How the alpha item's codestream codes red, green and blue as samples, from the matrix, primaries
 * and transfer it states, each it leaves out read as unspecified. An `iovl` alpha item's canvas is coded through it.
 */
@InternalFilmstripApi
public class HeifAlpha(
  public val item: Long,
  public val grid: HeifGrid?,
  public val premultiplied: Boolean,
  public val fullRange: Boolean,
  public val bitDepth: Int,
  public val overlay: HeifOverlay? = null,
  public val refusal: String? = null,
  public val reading: YcbcrReading = ycbcrReading(UNSPECIFIED, UNSPECIFIED, UNSPECIFIED),
)

/**
 * The alpha item [bytes] carry for their primary item, or null when they are not an AVIF or HEIC or carry none.
 *
 * An alpha item is one whose `iref auxl` points at the primary item and whose `auxC` names the AVIF or the HEVC alpha
 * auxiliary type. The first one the `iref` lists is taken. A file that is truncated or malformed carries none. Every
 * property is read from the first `iprp`, its first `ipco` and its own `ipma` boxes, as [metaParts] finds them. Only
 * the first 1024 of those `ipma` boxes are read, so an alpha item whose `auxC` or codec configuration is listed only in
 * a later one carries none, though libheif reads it.
 *
 * The range and depth are the alpha item's own, read from its codestream rather than any `nclx`. For HEVC that is the
 * first sequence parameter set in its `hvcC`, or the first at the start of its data when the `hvcC` holds none, with a
 * range it leaves out read as limited. For AV1 it is the first sequence header at the start of its data, or the first
 * in its `av1C` when the data holds none. A grid takes its first tile's and an `iovl` its first input's. An alpha
 * item coded as one image whose codestream does not read carries none either.
 *
 * An alpha item that is a `grid` or an `iovl` is read the way [heifPrimaryGrid] and [heifPrimaryOverlay] read a
 * primary item of that type. One they would refuse carries their reason as its [HeifAlpha.refusal], and one whose
 * layout or first codestream does not read carries [ALPHA_UNREADABLE_REASON], each with no layout and its range and
 * depth left at full range and eight bits. An `iovl` alpha item whose canvas shows and whose codestream's matrix
 * core cannot read carries that [YcbcrReading.Refused.reason] as its refusal, since its canvas cannot be coded.
 */
@InternalFilmstripApi
public fun heifAlpha(bytes: ByteArray): HeifAlpha? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.alpha() }
}

private fun ByteView.alpha(): HeifAlpha? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val primary = parts.primary ?: return null
  val iref = parts.iref ?: return null
  if (parts.ipco == null) return null
  val references = heif.within(iref.end).references(iref).toList()

  val listed = parts.properties
  val ipmas = parts.ipmas
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
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  val derived = types[alpha] == GRID || types[alpha] == IOVL
  val refused = { reason: String -> HeifAlpha(alpha, null, premultiplied, true, BYTE_BITS, refusal = reason) }
  val unread = { if (derived) refused(ALPHA_UNREADABLE_REASON) else null }
  val tiled = if (types[alpha] == GRID) grid(alpha, iref, parts) ?: return unread() else null
  val overlay = if (types[alpha] == IOVL) overlay(alpha, iref, parts, types) ?: return unread() else null
  val refusal = (tiled as? HeifGridRead.Refused)?.reason ?: (overlay as? HeifOverlayRead.Refused)?.reason
  if (refusal != null) return refused(refusal)
  val grid = (tiled as? HeifGridRead.Read)?.grid
  val laid = (overlay as? HeifOverlayRead.Read)?.overlay
  val coded = grid?.tiles?.first() ?: laid?.inputs?.first()?.item ?: alpha
  val configuration = heif.configurationOf(coded, codecOf(types[coded]), ipmas, listed) ?: return unread()
  val signal = codestream(coded, configuration, parts) ?: return unread()
  val reading = signal.reading
  if (reading is YcbcrReading.Refused && laid?.fillShown == true) return refused(reading.reason)
  return HeifAlpha(alpha, grid, premultiplied, signal.fullRange, signal.bitDepth, laid, reading = reading)
}

/**
 * The reason a `grid` or `iovl` alpha item that does not read is refused.
 *
 * Its layout or the codestream of its first tile or input is malformed, so its plane cannot be put together.
 */
@InternalFilmstripApi
public const val ALPHA_UNREADABLE_REASON: String =
  "The overlay image's alpha is put together from other images in a way that does not read, so this overlay " +
    "picture cannot be drawn."

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

// The range, depth and reading of [item]'s codestream, from the unit heifAlpha takes among those its [configuration]
// and the start of its data carry, or null when there is none or it does not read.
private fun ByteView.codestream(
  item: Long,
  configuration: Box,
  parts: MetaParts,
): AlphaSignal? {
  val codec = if (configuration.type == AV1C) CodecKind.Av1 else CodecKind.Hevc
  val coded = listOf(item to configuration)
  val configured = codestreamUnits(codec, coded, null, null)
  val all = codestreamUnits(codec, coded, parts.locations, parts.idat)
  val inBand = all.drop(configured.size)
  val unit = (if (codec == CodecKind.Av1) inBand + configured else configured + inBand).firstOrNull() ?: return null
  val depth = samplesOf(unit)?.bitDepth ?: return null
  val signal = signalOf(unit) ?: return null
  val reading =
    ycbcrReading(signal.matrix ?: UNSPECIFIED, signal.primaries ?: UNSPECIFIED, signal.transfer ?: UNSPECIFIED)
  return AlphaSignal(signal.fullRange == true, depth, reading)
}

private class AlphaSignal(
  val fullRange: Boolean,
  val bitDepth: Int,
  val reading: YcbcrReading,
)

// Whether [property] is an auxC naming the AVIF or the HEVC alpha auxiliary type.
internal fun ByteView.isAlphaType(property: Box): Boolean {
  if (property.type != AUXC) return false
  val urn = within(property.end)
  val start = property.start + 4
  return ALPHA_TYPES.any { type ->
    urn.matches(start, type) && (start + type.size == urn.end || urn.u8(start + type.size) == 0)
  }
}

private val AUXL = fourcc("auxl")
internal val AUXC = fourcc("auxC")
private val PREM = fourcc("prem")
private const val BYTE_BITS = 8
private const val UNSPECIFIED = 2
private val ALPHA_TYPES =
  listOf(ascii("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"), ascii("urn:mpeg:hevc:2015:auxid:1"))
