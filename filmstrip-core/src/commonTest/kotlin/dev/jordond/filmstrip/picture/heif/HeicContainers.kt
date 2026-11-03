package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.picture.fixtures.BoxSize
import dev.jordond.filmstrip.picture.fixtures.PROFILE
import dev.jordond.filmstrip.picture.fixtures.asciiBytes
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.colr
import dev.jordond.filmstrip.picture.fixtures.ftyp
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.id
import dev.jordond.filmstrip.picture.fixtures.iinf
import dev.jordond.filmstrip.picture.fixtures.nclx
import dev.jordond.filmstrip.picture.fixtures.sizedBox

// Builds HEIC files around hand-built parameter sets, every box size and item location worked out from scratch.

/**
 * A HEIC whose `hvcC` carries [sps], laid out so a file rewritten in place can be compared byte for byte with one
 * built around the rewritten units.
 *
 * The coded items hold a length prefixed slice, behind a video, [inBand] sequence and picture parameter set when
 * [inBand] is set. An `Exif` item sits in `mdat` after them and a `mime` item in an `idat` at the end of `meta`, so
 * both kinds of location move. With [grid] the primary item is a 2 by 2 `grid` whose descriptor sits in `idat` ahead
 * of the `mime` data, derived from four coded tiles, items 2 to 5, sharing one `hvcC` unless [tiles] gives a tile its
 * own.
 *
 * @property sps The sequence parameter sets of the `hvcC`, one array each.
 * @property nclx The primary item's `colr nclx`, or null for none.
 * @property profile Whether the primary item also has a `colr prof`.
 * @property large Whether `meta`, `iprp`, `ipco` and `mdat` use 64-bit sizes.
 * @property baseOffset Whether `iloc` gives each `mdat` item a base offset at the start of `mdat`'s payload.
 * @property ilocVersion The `iloc` version. Version 0 has no construction method, so the `mime` item moves to the end
 * of `mdat` and [grid] and [codedInIdat] are not allowed.
 * @property itemType The coded items' type.
 * @property trailing The type of an empty box after `mdat`, or null for none.
 * @property junk Bytes after every box that do not parse as one, or null for none.
 * @property mdatFirst Whether `mdat` comes ahead of `meta`, so its items sit before every change.
 * @property codedInIdat Whether the coded items sit in `idat` after the `mime` data, construction method 1.
 * @property openEnded Whether the last item in `idat` has an extent length of 0, running to the end of `idat`.
 * @property splitExtents Whether each coded item is split into two extents, the second holding its last four bytes.
 * @property lengthSizeMinusOne The `hvcC` field giving the width of the length ahead of each unit in the item data.
 * @property ilocBox An `iloc` box that replaces the one worked out from the layout, or null to keep it.
 * @property configuration A coding configuration box that replaces the `hvcC`, such as an `av1C`, or null to keep it.
 * @property coded The data each coded item holds in place of the units [payload] lays out, or null to keep them.
 * @property tiles What sets a tile of [grid] apart from the rest, keyed by its item id.
 * @property secondNclx A second `colr nclx` the primary item is associated with after [nclx], or null for none.
 * @property thumbnailNclx The `colr nclx` of a thumbnail, item 7, coded like the primary item and referring to it with
 * a `thmb` reference, or null for no thumbnail.
 * @property transforms Properties such as `irot` and `imir` added to `ipco` after the rest, before the thumbnail's.
 * @property transformed The items whose `ipma` entries list [transforms], right after their first association.
 * @property ipmaVersion The `ipma` version, 1 for 32-bit item ids.
 * @property wideIndex Whether `ipma` writes each property index in 15 bits rather than 7.
 * @property alphas How many alpha items, from item 10 on, are coded like the primary item with an `auxC` and an `auxl`
 * reference to it.
 * @property alphaTransforms Properties added to `ipco` after [transforms] that an alpha item in [transformed] lists in
 * their place, or null for it to list [transforms].
 * @property largeIpma Whether `ipma` uses a 64-bit size.
 * @property splitIpma Whether every entry after the first goes in a second `ipma` box.
 * @property nonEssential The property indices `ipma` writes without the essential bit.
 * @property onlyTransforms Whether the primary item lists [transforms] alone, or nothing when it is not in
 * [transformed].
 * @property irefVersion The `iref` version, 1 for 32-bit item ids.
 * @property auxiliaryType The payload of the `auxC` the [alphas] items share past its version and flags, the alpha URN
 * and a zero byte unless set. Another URN makes them auxiliary items of another kind.
 * @property emptyAlphaEntries Whether each of the [alphas] items has an `ipma` entry listing nothing.
 */
internal class TestHeic(
  val sps: List<ByteArray>,
  val nclx: Cicp? = Cicp(1, 13, 1, true),
  val profile: Boolean = false,
  val inBand: ByteArray? = null,
  val grid: Boolean = false,
  val large: Boolean = false,
  val baseOffset: Boolean = false,
  val ilocVersion: Int = 1,
  val itemType: String = "hvc1",
  val trailing: String? = null,
  val junk: ByteArray? = null,
  val mdatFirst: Boolean = false,
  val codedInIdat: Boolean = false,
  val openEnded: Boolean = false,
  val splitExtents: Boolean = false,
  val lengthSizeMinusOne: Int = 3,
  val ilocBox: ByteArray? = null,
  val configuration: ByteArray? = null,
  val coded: ByteArray? = null,
  val tiles: Map<Int, TestTile> = emptyMap(),
  val secondNclx: Cicp? = null,
  val thumbnailNclx: Cicp? = null,
  val transforms: List<ByteArray> = emptyList(),
  val transformed: List<Int> = listOf(1),
  val ipmaVersion: Int = 0,
  val wideIndex: Boolean = false,
  val alphas: Int = 0,
  val alphaTransforms: List<ByteArray>? = null,
  val largeIpma: Boolean = false,
  val splitIpma: Boolean = false,
  val nonEssential: Set<Int> = emptySet(),
  val onlyTransforms: Boolean = false,
  val irefVersion: Int = 0,
  val auxiliaryType: ByteArray = asciiBytes(ALPHA_URN) + ByteArray(1),
  val emptyAlphaEntries: Boolean = false,
) {
  private val codedItems = if (grid) listOf(2, 3, 4, 5) else listOf(1)
  private val size = if (large) BoxSize.Large else BoxSize.Exact
  private val alphaItems = List(alphas) { FIRST_ALPHA_ITEM + it }

  init {
    require(ilocVersion != 0 || !(grid || codedInIdat)) { "iloc version 0 has no construction method" }
    require(tiles.keys.all { it in codedItems && grid }) { "only the tiles of a grid can be set apart" }
    require(!onlyTransforms || !grid) { "a grid lists its own associations" }
  }

  /**
   * The data each coded item holds. Every unit in it sits behind a length as wide as [lengthSizeMinusOne] gives, in
   * the order the parameter sets come and then the slice.
   */
  val payload: ByteArray = coded ?: laidOut(inBand)

  private fun laidOut(inBand: ByteArray?): ByteArray =
    bytes {
      val units = (if (inBand != null) listOf(VPS_NAL, inBand, PPS_NAL) else emptyList()) + listOf(SLICE)
      for (unit in units) {
        when (lengthSizeMinusOne) {
          0 -> u8(unit.size)
          1 -> u16(unit.size)
          2 -> u8(unit.size ushr 16).u16(unit.size and 0xFFFF)
          else -> u32(unit.size.toLong())
        }
        raw(unit)
      }
    }

  private val placed: List<Placed> =
    buildList {
      if (grid) add(Placed(1, inIdat = true, GRID))
      if (ilocVersion != 0) add(Placed(MIME_ITEM, inIdat = true, MIME))
      codedItems.forEach { add(Placed(it, codedInIdat, dataOf(it))) }
      if (thumbnailNclx != null) add(Placed(THUMBNAIL_ITEM, inIdat = false, payload))
      alphaItems.forEach { add(Placed(it, inIdat = false, payload)) }
      add(Placed(EXIF_ITEM, inIdat = false, EXIF))
      if (ilocVersion == 0) add(Placed(MIME_ITEM, inIdat = false, MIME))
    }

  // The data a coded item holds, its tile's own when tiles sets it apart.
  private fun dataOf(item: Int): ByteArray {
    val tile = tiles[item] ?: return payload
    return tile.coded ?: tile.inBand?.let(::laidOut) ?: payload
  }

  fun bytes(): ByteArray {
    val fileType = ftyp("heic", "mif1")
    val mdatHeader = if (large) 16 else 8
    val mdatPayload = fileType.size + (if (mdatFirst) 0 else meta(0).size) + mdatHeader
    val meta = meta(mdatPayload)
    val mdat = sizedBox("mdat", size, *placed.filter { !it.inIdat }.map { it.data }.toTypedArray())
    return bytes {
      raw(fileType)
      if (mdatFirst) raw(mdat).raw(meta) else raw(meta).raw(mdat)
      trailing?.let { raw(box(it, ByteArray(8))) }
      junk?.let { raw(it) }
    }
  }

  private fun meta(mdatPayload: Int): ByteArray {
    val thumbnail = if (thumbnailNclx != null) listOf(THUMBNAIL_ITEM to itemType) else emptyList()
    val alpha = alphaItems.map { it to itemType }
    val types = codedItems.map { it to itemType } + thumbnail + listOf(EXIF_ITEM to "Exif", MIME_ITEM to "mime") + alpha
    val auxiliary = alphaItems.map { reference("auxl", it, listOf(1)) }
    val references =
      listOfNotNull(
        if (grid) reference("dimg", 1, codedItems) else null,
        if (thumbnailNclx != null) reference("thmb", THUMBNAIL_ITEM, listOf(1)) else null,
      ) + auxiliary
    val children =
      listOfNotNull(
        fullBox("hdlr", 0, 0, ByteArray(21)),
        fullBox("pitm", 0, 0, bytes { u16(1) }),
        ilocBox ?: iloc(mdatPayload),
        iinf(*((if (grid) listOf(1 to "grid") else emptyList()) + types).toTypedArray()),
        if (references.isNotEmpty()) fullBox("iref", irefVersion, 0, *references.toTypedArray()) else null,
        iprp(),
        box("idat", *placed.filter { it.inIdat }.map { it.data }.toTypedArray()),
      )
    return sizedBox("meta", size, bytes { u32(0) }, *children.toTypedArray())
  }

  private fun iprp(): ByteArray {
    val colors =
      listOfNotNull(if (profile) colr("prof", PROFILE) else null, nclx?.let(::nclxBox), secondNclx?.let(::nclxBox))
    val configuration =
      configuration ?: hvccOf(VPS_NAL, *sps.toTypedArray(), PPS_NAL, lengthSizeMinusOne = lengthSizeMinusOne)
    val shared = listOf(configuration, ispe(64)) + colors + ispe(128)
    val own = codedItems.mapNotNull { item -> tiles[item]?.configuration?.let { item to it } }
    val auxiliary = if (alphas > 0) listOf(fullBox("auxC", 0, 0, auxiliaryType)) else listOf()
    val ofAlpha = alphaTransforms.orEmpty() + auxiliary
    val properties =
      shared + own.map { it.second } + transforms + ofAlpha + listOfNotNull(thumbnailNclx?.let(::nclxBox))
    val ownIndex = own.mapIndexed { at, (item, _) -> item to shared.size + 1 + at }.toMap()
    val colorIndices = colors.indices.map { it + 3 }
    val transformIndices = transforms.indices.map { shared.size + own.size + 1 + it }
    val afterTransforms = shared.size + own.size + transforms.size
    val alphaIndices = alphaTransforms?.indices?.map { afterTransforms + 1 + it } ?: transformIndices
    val auxCIndex = afterTransforms + ofAlpha.size
    val turns = { item: Int ->
      when {
        item !in transformed -> emptyList()
        item in alphaItems -> alphaIndices
        else -> transformIndices
      }
    }
    val listed = { item: Int, first: Int, rest: List<Int> -> item to listOf(first) + turns(item) + rest }
    val primary =
      when {
        grid -> {
          listOf(listed(1, shared.size, colorIndices)) + codedItems.map { listed(it, ownIndex[it] ?: 1, listOf(2)) }
        }
        onlyTransforms -> {
          listOf(1 to turns(1))
        }
        else -> {
          listOf(listed(1, 1, listOf(2) + colorIndices))
        }
      }
    val thumbnail = thumbnailNclx?.let { listOf(listed(THUMBNAIL_ITEM, 1, listOf(2, properties.size))) }.orEmpty()
    val alpha = alphaItems.map { if (emptyAlphaEntries) it to emptyList() else listed(it, 1, listOf(2, auxCIndex)) }
    val associations = primary + thumbnail + alpha
    val boxes = if (splitIpma) listOf(associations.take(1), associations.drop(1)) else listOf(associations)
    val ipmas = boxes.map(::ipmaOf).toTypedArray()
    return sizedBox("iprp", size, sizedBox("ipco", size, *properties.toTypedArray()), *ipmas)
  }

  // An ipma box listing each item's property indices, essential unless nonEssential names them.
  private fun ipmaOf(entries: List<Pair<Int, List<Int>>>): ByteArray {
    val body =
      bytes {
        u8(ipmaVersion).u8(0).u16(if (wideIndex) 1 else 0)
        u32(entries.size.toLong())
        for ((item, indices) in entries) {
          id(item, ipmaVersion)
          u8(indices.size)
          for (index in indices) {
            val essential = if (index in nonEssential) 0 else 1
            if (wideIndex) u16((essential shl 15) or index) else u8((essential shl 7) or index)
          }
        }
      }
    return sizedBox("ipma", if (largeIpma) BoxSize.Large else BoxSize.Exact, body)
  }

  // A single item reference of type from item from to the items to lists, its ids as wide as irefVersion makes them.
  private fun reference(
    type: String,
    from: Int,
    to: List<Int>,
  ): ByteArray =
    box(
      type,
      bytes {
        id(from, irefVersion).u16(to.size)
        to.forEach { id(it, irefVersion) }
      },
    )

  private fun iloc(mdatPayload: Int): ByteArray {
    val idVersion = if (ilocVersion == 2) 1 else 0
    val lastInIdat = placed.lastOrNull { it.inIdat }
    val body =
      bytes {
        u8(0x44).u8(if (baseOffset) 0x40 else 0)
        id(placed.size, idVersion)
        for (item in placed) {
          id(item.item, idVersion)
          if (ilocVersion != 0) u16(if (item.inIdat) 1 else 0)
          u16(0)
          val base = if (item.inIdat) 0L else mdatPayload.toLong()
          if (baseOffset) u32(base)
          val offset = offsetOf(item) + if (baseOffset) 0L else base
          val extents =
            when {
              openEnded && item === lastInIdat -> {
                listOf(offset to 0L)
              }
              splitExtents && item.item in codedItems -> {
                val first = item.data.size - 4L
                listOf(offset to first, offset + first to 4L)
              }
              else -> {
                listOf(offset to item.data.size.toLong())
              }
            }
          u16(extents.size)
          extents.forEach { (at, length) -> u32(at).u32(length) }
        }
      }
    return fullBox("iloc", ilocVersion, 0, body)
  }

  // Where the item's data starts in its area, after every item placed ahead of it there.
  private fun offsetOf(item: Placed): Long =
    placed.takeWhile { it !== item }.filter { it.inIdat == item.inIdat }.sumOf { it.data.size.toLong() }

  private fun ispe(side: Int): ByteArray = fullBox("ispe", 0, 0, bytes { u32(side.toLong()).u32(side.toLong()) })

  private class Placed(
    val item: Int,
    val inIdat: Boolean,
    val data: ByteArray,
  )

  internal companion object {
    const val THUMBNAIL_ITEM = 7
    const val EXIF_ITEM = 8
    const val MIME_ITEM = 9
    const val FIRST_ALPHA_ITEM = 10
    const val ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
    val SLICE = byteArrayOf(0x02, 0x01, 0xD0.toByte(), 0x00, 0x00, 0x03, 0x01, 0x7F)
    val EXIF = asciiBytes("Exif") + byteArrayOf(0, 0, 0x4D, 0x4D, 0, 0x2A)
    val MIME = asciiBytes("<x:xmpmeta/>")
    val GRID = byteArrayOf(0, 0, 1, 1, 0, 0x80.toByte(), 0, 0x80.toByte())
  }
}

/**
 * What sets one tile of a [TestHeic] grid apart from the rest, each part null to take the file's own.
 *
 * @property configuration The tile's own coding configuration box, such as an `hvcC` or `av1C`, in place of the one
 * the other tiles share.
 * @property inBand A sequence parameter set the tile's data carries in band, laid out the way [TestHeic.inBand] is.
 * @property coded The data the tile holds, in place of the units [TestHeic.payload] lays out.
 */
internal class TestTile(
  val configuration: ByteArray? = null,
  val inBand: ByteArray? = null,
  val coded: ByteArray? = null,
)

internal fun nclxBox(cicp: Cicp): ByteArray =
  box("colr", asciiBytes("nclx") + codePoints(cicp) + byteArrayOf(if (cicp.fullRange) 0x80.toByte() else 0))

private fun codePoints(cicp: Cicp): ByteArray = bytes { u16(cicp.primaries).u16(cicp.transfer).u16(cicp.matrix) }
