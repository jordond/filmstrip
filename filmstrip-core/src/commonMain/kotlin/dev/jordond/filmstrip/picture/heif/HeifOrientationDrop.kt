package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.container
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf

// Drops the irot, imir and clap associations of an AVIF or HEIC's primary item and its alpha items from their ipma
// boxes, so a decoder hands the samples back as stored and uncropped and core alone crops and turns them.

/**
 * [bytes] with the `irot`, `imir` and `clap` associations of an AVIF or HEIC's primary item, and of its alpha items,
 * dropped from their `ipma` entries, so a decoder hands back the whole stored picture and [pictureOrientationOf] on the
 * result reads [PictureOrientation.Normal]. Read the orientation and [pictureCropOf] from [bytes], not from the result,
 * and crop by the one before turning by the other. The color is left as it is, which
 * [heifWithExplicitCodestreamColor] states in the same pass.
 *
 * The properties stay in `ipco` and every other association keeps its place and bits. An alpha item, one whose `auxl`
 * reference names the primary item and whose `auxC` names an alpha type, is cropped and turned by its own associations
 * and drawn with the primary item, so its associations are dropped too. A gain map, depth or matte item, up to 256 of
 * them, a thumbnail and a tile keep theirs and never stop the drop. Box sizes and item locations move with the dropped
 * bytes. Every item is read from the file's first `iprp`, as [pictureOrientationOf]
 * reads it.
 *
 * A file is tagged when its primary item or an alpha item lists a `clap`, an `imir` or an `irot` of anything but 0,
 * even when they fold back into [PictureOrientation.Normal], or a later `ipma` entry for one of them lists one.
 * Gives [UnturnedHeif.Untagged] with [bytes] itself for anything but an AVIF or HEIC and for an untagged one, an `irot`
 * of 0 alone and an image sequence with a `moov` box included. A tagged file is [UnturnedHeif.Dropped], or
 * [UnturnedHeif.Refused] when
 * - it has a `moov` box, whose sample tables hold offsets of their own,
 * - it has more than 15 alpha items,
 * - the primary item or an alpha item has a second `ipma` entry,
 * - an alpha item's `irot` and `imir` fold into another orientation than the primary item's,
 * - an `iscl` is listed where the `irot` and `imir` ahead of it fold into a turn or mirror, since it scales the turned
 * picture,
 * - the primary item's `clap` reads no crop, or an alpha item's `clap` crops it to another rectangle than the primary
 * item's,
 * - a dropped byte falls inside an item's data,
 * - it has more than 1024 top-level boxes,
 * - the file without them does not read back.
 *
 * A `clap` listed after a turn is dropped like one before it, since
 * [pictureCropOf] carries it back to the stored picture.
 *
 * A file whose first `iprp` holds an `ipco` with no `irot`, `imir` or `clap` among the boxes that parse is untagged, a
 * truncated or malformed one included. Past that, one any part of which the drop reads does not parse is refused too,
 * and so is one that names no primary item, or one with more than 1024 `ipma` boxes or more than 256 items naming its
 * primary item through `auxl`, as tagged or too large to tell. So is one with an `ipma` above version 1, or whose
 * primary item lists an `ispe` above version 0 or has no entry in the first `iprp`, which libheif refuses and core
 * never turns. It never throws.
 */
@InternalFilmstripApi
public fun heifWithoutOrientation(bytes: ByteArray): UnturnedHeif {
  val file = ByteView(bytes, bytes.size)
  if (file.container() != PictureContainer.Heif || !file.holdsTaken()) return UnturnedHeif.Untagged(bytes)
  return deferring { file.withoutOrientation() } ?: UnturnedHeif.Refused(UNPARSED)
}

// Whether an irot, imir or clap parses in the first ipco of the first iprp of the first meta box, each walk stopping
// at the first box that does not parse.
private fun ByteView.holdsTaken(): Boolean = metaParts(lenient = true)?.properties.orEmpty().any { it.type in TAKEN }

/**
 * This file with every `irot`, `imir` and `clap` association of its primary item, and of each of its alpha items,
 * dropped. A decoder handed it decodes the whole stored picture, neither cropped nor turned, and a backend crops it by
 * [heifCrop] and turns it by [heifOrientation], both read from the input.
 *
 * An alpha item is one whose `auxl` reference names the primary item and that is associated with an `auxC` naming an
 * alpha type, as [isAlphaType] reads it, the same items [heifAlpha] takes its alpha from. Other auxiliary items, up to
 * [MAX_AUXL_SOURCES] of them, such as a gain map, depth or matte, are left as they are and never stop the drop.
 *
 * Every item is read from the first `iprp`, its first `ipco` and its own `ipma` boxes. The primary item's properties
 * are the ones [heifListing] lists, and an alpha item's are the ones its first entry lists. The file is tagged when one
 * of them lists a `clap`, an `imir` or an `irot` of anything but 0, even when they fold back into
 * [PictureOrientation.Normal], or when a later entry for one of them lists an `irot`, `imir` or `clap`. An `irot` of 0
 * alone leaves a file untagged. An untagged file is [UnturnedHeif.Untagged] with its own bytes, whatever else it holds.
 *
 * Once its `ipco` holds an `irot`, `imir` or `clap`, a file is [UnturnedHeif.Refused] wherever [heifListing] refuses
 * the primary item, for an `ipma` above version 1, an `ispe` above version 0 or no entry for it in the first `iprp`,
 * since core never turns such a file and a decoder may. A tagged file is refused when it has a `moov` box, more than
 * [MAX_DROPPED_ITEMS] items to drop from or a second `ipma` entry for one of them. It is refused when an alpha item's
 * `irot` and `imir` fold into another orientation than the primary item's, as [heifTransformsOf] folds them in listing
 * order, when an `iscl` on one of them scales a turned or mirrored picture, which [HeifTransforms.scalesTurned] reads,
 * or when a dropped byte sits inside an item's data. A `clap` listed after a turn is dropped like any other, since
 * [heifCrop] carries it back to the stored picture. It is refused when the primary item lists a `clap` [heifCropOf]
 * reads no crop for, or an alpha item lists one that crops it to another rectangle than the primary item's, since a
 * backend crops both by the primary item's. It is refused too when it names no primary item, has more than
 * [MAX_LISTED_BOXES] top-level boxes, or the result does not read back.
 *
 * Which items are tagged is read from the first [MAX_LISTED_BOXES] `ipma` boxes and the first [MAX_AUXL_SOURCES] items
 * an `auxl` reference names the primary item for. A file holding more of either is refused whenever its `ipco` holds
 * an `irot`, `imir` or `clap`, as tagged or too large to tell.
 *
 * Only the associations go. The properties stay in `ipco`, so every property index holds, and each entry keeps its
 * other associations in their order, essential bits included, with its count lowered. Every other item's entries, a
 * thumbnail's or a tile's `irot` or `clap` included, stay as they were. The `ipma`, `iprp` and `meta` boxes shrink with
 * the dropped bytes, a 64-bit size included, and every `iloc` base offset, extent offset and extent length the change
 * moves is fixed.
 *
 * The result is read back before it is given. Its top-level boxes must walk to where the input's end up, every item
 * extent must hold the input's bytes, and its associations must be the input's without the dropped ones.
 *
 * Throws [MalformedPicture] when a box it reads does not parse.
 */
internal fun ByteView.withoutOrientation(): UnturnedHeif {
  val untagged = UnturnedHeif.Untagged(bytes)
  val parts = metaParts() ?: return untagged
  val properties = parts.properties
  val taken = properties.indicesWhere { it.type in TAKEN }
  if (taken.isEmpty()) return untagged
  if (parts.pastCap) return UnturnedHeif.Refused(TOO_MANY_LISTED)
  val primary = parts.primary ?: return UnturnedHeif.Refused(NO_PRIMARY)
  if (parts.ipmas.any { within(it.end).u8(it.start) > 1 }) return UnturnedHeif.Refused(IPMA_ABOVE_VERSION_1)
  val named = auxlSources(parts, primary) ?: return UnturnedHeif.Refused(TOO_MANY_AUXILIARY)
  val takesAt = { index: Int -> index in taken && takes(properties[index - 1]) }
  val entries = entriesOf(parts.ipmas, named + primary, takesAt, properties.indicesWhere { isAlphaType(it) })
  val listing =
    heifListing(parts.ipmas, primary, properties)
      ?: return UnturnedHeif.Refused(if (primary in entries.first) ISPE_ABOVE_VERSION_0 else UNLISTED)
  val alphas = named.filter { it in entries.typed }
  val items = alphas.toSet() + primary
  val shown = heifTransformsOf(listing.asSequence())
  val alphaListings = alphas.map { alpha -> listingOf(entries.first[alpha], properties) }
  val alphasShown = alphaListings.map { heifTransformsOf(it.asSequence()) }
  val tagged =
    (alphaListings + listOf(listing)).any { boxes -> boxes.any { takes(it) } } ||
      items.any { it in entries.takenAgain }
  if (!tagged) return untagged
  val top = leadingBoxes(MAX_LISTED_BOXES + 1)
  if (top.isEmpty()) return UnturnedHeif.Refused(UNPARSED)
  if (top.size > MAX_LISTED_BOXES) return UnturnedHeif.Refused(TOO_MANY_LISTED)
  if (top.any { it.type == MOOV }) return UnturnedHeif.Refused(SEQUENCE)
  if (alphas.size > MAX_DROPPED_ITEMS - 1) return UnturnedHeif.Refused(TOO_MANY_ALPHAS)
  if (items.any { it in entries.again }) return UnturnedHeif.Refused(SECOND_ENTRY)
  if (alphasShown.any { it.orientation != shown.orientation }) return UnturnedHeif.Refused(ALPHA_TURNED_ANOTHER_WAY)
  if (shown.scalesTurned || alphasShown.any { it.scalesTurned }) return UnturnedHeif.Refused(SCALED_AFTER)
  cropRefusal(listing, alphaListings)?.let { return UnturnedHeif.Refused(it) }
  val sorted = items.mapNotNull { entries.first[it]?.let { entry -> dropping(entry, taken) } }.sortedBy { it.start }
  val locations = parts.locations
  if (locations != null && coversItemData(locations, parts.idat, sorted)) return UnturnedHeif.Refused(IN_ITEM_DATA)
  return dropped(top, parts, items, taken, sorted)?.let(UnturnedHeif::Dropped)
    ?: UnturnedHeif.Refused(NOT_READ_BACK)
}

// Why the primary item's crop cannot stand in for the clap associations the drop takes off listing and the alpha
// listings, or null when it can: the primary item's clap reads no crop, or an alpha item's clap crops another way.
private fun ByteView.cropRefusal(
  listing: List<Box>,
  alphaListings: List<List<Box>>,
): String? {
  val clapped = { boxes: List<Box> -> boxes.any { it.type == CLAP } }
  if (!clapped(listing) && alphaListings.none(clapped)) return null
  val crop = heifCropOf(listing) ?: return CROP_UNREAD
  return ALPHA_CROPPED_ANOTHER_WAY.takeIf { alphaListings.any { clapped(it) && heifCropOf(it) != crop } }
}

// The file with the sorted splices made and its sizes and locations moved, or null when it does not read back with
// the items' taken associations gone and everything else kept.
private fun ByteView.dropped(
  top: List<Box>,
  parts: MetaParts,
  items: Set<Long>,
  taken: Set<Int>,
  sorted: List<Splice>,
): ByteArray? {
  val locations = parts.locations
  val fields = mutableListOf<Field>()
  sizeFields(top, sorted, fields)
  if (locations != null) fields += locations.fields(sorted, parts.idat) ?: return null
  val rewritten = Rewritten(spliced(bytes, fields, sorted) ?: return null, sorted)
  if (!rewritten.keepsBoxes(top) || !rewritten.keepsItemData(this, locations, parts.idat)) return null
  val kept = listed(parts.ipmas).filterNot { it.item in items && it.index in taken }
  val now = rewritten.file.metaParts()?.let { rewritten.file.listed(it.ipmas) } ?: return null
  return rewritten.bytes.takeIf { kept.sameAs(now) }
}

// One association an ipma entry lists: the one-based ipco index, and where its bytes sit and how many.
private class Association(
  val index: Int,
  val at: Int,
  val width: Int,
)

// One ipma entry: where its association count sits and the associations it lists.
private class Entry(
  val countAt: Int,
  val listed: List<Association>,
)

// An association as its item, its index and the value it is written with, essential bit included.
private data class Listed(
  val item: Long,
  val index: Int,
  val value: Int,
)

// What the ipma boxes list for the items the drop reads: each one's first entry, the ones with a later entry, the ones
// whose later entry lists a property the drop takes and the ones any entry associates with an alpha auxC.
private class Entries(
  val first: Map<Long, Entry>,
  val again: Set<Long>,
  val takenAgain: Set<Long>,
  val typed: Set<Long>,
)

// The one-based index of every property that matches, among the first 32767, the most an ipma entry can address.
private inline fun List<Box>.indicesWhere(matches: (Box) -> Boolean): Set<Int> =
  buildSet { this@indicesWhere.forEachIndexed { at, box -> if (matches(box)) add(at + 1) } }

// The items an auxl reference in iref names primary for, in the order iref first names them, or null past
// MAX_AUXL_SOURCES of them. The walk stops there, so nothing kept grows with the file.
private fun ByteView.auxlSources(
  parts: MetaParts,
  primary: Long,
): Set<Long>? {
  val named = linkedSetOf<Long>()
  val iref = parts.iref ?: return named
  for (reference in within(iref.end).references(iref)) {
    val from = reference.from
    if (reference.type != AUXL || from == primary || from in named || primary !in reference.to) continue
    if (named.size == MAX_AUXL_SOURCES) return null
    named += from
  }
  return named
}

// The entries of items across the ipma boxes. A first entry's associations are kept, and a later entry's indices are
// scanned where they sit only to tell whether they type the item or list an index takes accepts, a property a decoder
// crops, turns or mirrors by. Other items' associations are stepped over unread.
private fun ByteView.entriesOf(
  ipmas: List<Box>,
  items: Set<Long>,
  takes: (Int) -> Boolean,
  alphaTypes: Set<Int>,
): Entries {
  val first = mutableMapOf<Long, Entry>()
  val again = mutableSetOf<Long>()
  val takenAgain = mutableSetOf<Long>()
  val typed = mutableSetOf<Long>()
  for (ipma in ipmas) {
    val box = within(ipma.end)
    val width = if (box.u8(ipma.start + 3) and 1 == 1) 2 else 1
    box.forEachEntry(ipma) { item, start, count ->
      if (item in items) {
        if (box.anyIndex(ipma, start, count) { it in alphaTypes }) typed += item
        if (item !in first) {
          val listed = List(count) { Association(box.associationIndex(ipma, start, it), start + it * width, width) }
          first[item] = Entry(start - 1, listed)
        } else {
          again += item
          if (box.anyIndex(ipma, start, count, takes)) takenAgain += item
        }
      }
      true
    }
  }
  return Entries(first, again, takenAgain, typed)
}

// Whether any of the count associations of the entry in ipma whose first starts at start holds an index that matches.
private inline fun ByteView.anyIndex(
  ipma: Box,
  start: Int,
  count: Int,
  matches: (Int) -> Boolean,
): Boolean {
  for (at in 0 until count) if (matches(associationIndex(ipma, start, at))) return true
  return false
}

// The properties entry lists, in its order, an index outside properties passed over. An item with no entry lists none.
private fun listingOf(
  entry: Entry?,
  properties: List<Box>,
): List<Box> = entry?.listed.orEmpty().mapNotNull { properties.getOrNull(it.index - 1) }

// Whether property is a clap, an imir or an irot of anything but 0, the ones a decoder crops, turns or mirrors by.
private fun ByteView.takes(property: Box): Boolean =
  property.type == CLAP || property.type == IMIR ||
    (property.type == IROT && within(property.end).u8(property.start) and 3 != 0)

// The splice writing entry's count and the associations it keeps over the old ones, or null when it drops nothing.
private fun ByteView.dropping(
  entry: Entry,
  taken: Set<Int>,
): Splice? {
  val kept = entry.listed.filterNot { it.index in taken }
  if (kept.size == entry.listed.size) return null
  val end = entry.listed.last().let { it.at + it.width }
  val replacement = ByteArray(1 + kept.sumOf { it.width })
  replacement[0] = kept.size.toByte()
  var written = 1
  for (each in kept) written = copyInto(replacement, written, each.at, each.at + each.width)
  return Splice(entry.countAt, end, replacement)
}

// Whether any splice changes a byte of an item's data in the file or in idat.
private fun ByteView.coversItemData(
  locations: ItemLocations,
  idat: Box?,
  splices: List<Splice>,
): Boolean =
  locations.items.any { location ->
    !location.external && location.method <= 1 &&
      location.extents.any { extent ->
        val (from, to) = extentBytes(location, extent, idat)
        splices.any { it.start < to && it.end > from }
      }
  }

// Every association the ipma boxes list, in order, read as the walk reaches it. The essential bit is left out of the
// index.
private fun ByteView.listed(ipmas: List<Box>): Sequence<Listed> =
  ipmas.asSequence().flatMap { ipma ->
    val box = within(ipma.end)
    sequence {
      box.forEachAssociationAt(ipma) { item, at, width ->
        val value = if (width == 2) box.u16(at) else box.u8(at)
        yield(Listed(item, box.associationIndex(ipma, at, 0), value))
      }
    }
  }

// Every association ipma lists as its item, where it sits and how wide it is.
private inline fun ByteView.forEachAssociationAt(
  ipma: Box,
  each: (item: Long, at: Int, width: Int) -> Unit,
) {
  val width = if (u8(ipma.start + 3) and 1 == 1) 2 else 1
  forEachEntry(ipma) { item, first, count ->
    for (at in 0 until count) each(item, first + at * width, width)
    true
  }
}

// Whether both give the same values in the same order and run out together.
private fun <T> Sequence<T>.sameAs(other: Sequence<T>): Boolean {
  val mine = iterator()
  val theirs = other.iterator()
  while (mine.hasNext()) if (!theirs.hasNext() || mine.next() != theirs.next()) return false
  return !theirs.hasNext()
}

private val TAKEN = setOf(IROT, IMIR, CLAP)
private val AUXL = fourcc("auxl")

/**
 * The most items [withoutOrientation] drops associations from, the primary item and its alpha items together. A tagged
 * file with more alpha items is refused.
 */
internal const val MAX_DROPPED_ITEMS = 16

/**
 * The most items [withoutOrientation] reads as naming the primary item through `auxl`. Past them a file whose `ipco`
 * holds an `irot`, `imir` or `clap` is refused.
 */
internal const val MAX_AUXL_SOURCES = 256

// Why a tagged file is refused, each a clause for UnturnedHeif.Refused.message.
internal const val UNPARSED = "its boxes do not parse"
internal const val NO_PRIMARY = "it names no primary item"
internal const val SEQUENCE = "it is an image sequence with a moov box"
internal val TOO_MANY_ALPHAS = "it has more than ${MAX_DROPPED_ITEMS - 1} alpha items"
internal val TOO_MANY_AUXILIARY = "more than $MAX_AUXL_SOURCES items refer to its primary item through auxl"
internal val TOO_MANY_LISTED = "it has more than $MAX_LISTED_BOXES top-level or ipma boxes"
internal const val IPMA_ABOVE_VERSION_1 = "an ipma box is above version 1"
internal const val ISPE_ABOVE_VERSION_0 = "its primary item lists an ispe above version 0"
internal const val UNLISTED = "its primary item has no ipma entry in the first iprp"
internal const val SECOND_ENTRY = "its primary item or an alpha item has a second ipma entry"
internal const val ALPHA_TURNED_ANOTHER_WAY = "an alpha item is turned another way than its primary item"
internal const val SCALED_AFTER = "an iscl scales a turned or mirrored picture"
internal const val CROP_UNREAD = "its primary item's clap reads no crop"
internal const val ALPHA_CROPPED_ANOTHER_WAY = "an alpha item is cropped another way than its primary item"
internal const val IN_ITEM_DATA = "a dropped byte sits inside an item's data"
internal const val NOT_READ_BACK = "the file without them does not read back"
