package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.malformed

// Records which properties an AVIF or HEIC's ipma boxes associate with its primary item and the tiles it is derived
// from, the first of each kind each item has.

/**
 * The first property index of each kind associated with the primary item and with each of its tiles, zero for none.
 */
internal class FirstProperties(
  tiles: LongArray,
) {
  private val items = tiles.sortedArray().distinctSorted()
  private val tileIndices = IntArray(items.size * PropertyKind.COUNT)
  val primary = IntArray(PropertyKind.COUNT)

  fun record(
    item: Long,
    primaryItem: Long,
    kind: Int,
    index: Int,
  ) {
    if (item == primaryItem) {
      if (primary[kind] == 0) primary[kind] = index
      return
    }
    val at = position(item)
    if (at < 0) return
    val slot = at * PropertyKind.COUNT + kind
    if (tileIndices[slot] == 0) tileIndices[slot] = index
  }

  // The property of [kind] on the first of [tiles], in the order the primary item lists them, that has one.
  fun firstTile(
    tiles: LongArray,
    kind: Int,
  ): Int {
    for (tile in tiles) {
      val found = ofTile(tile, kind)
      if (found != 0) return found
    }
    return 0
  }

  fun ofTile(
    tile: Long,
    kind: Int,
  ): Int = position(tile).let { at -> if (at >= 0) tileIndices[at * PropertyKind.COUNT + kind] else 0 }

  private fun position(item: Long): Int {
    var low = 0
    var high = items.size - 1
    while (low <= high) {
      val middle = (low + high) ushr 1
      when {
        items[middle] < item -> low = middle + 1
        items[middle] > item -> high = middle - 1
        else -> return middle
      }
    }
    return -1
  }

  private fun LongArray.distinctSorted(): LongArray {
    var count = 0
    for (value in this) if (count == 0 || this[count - 1] != value) this[count++] = value
    return copyOf(count)
  }
}

internal fun ByteView.recordProperties(
  ipma: Box,
  primary: Long,
  kinds: ByteArray,
  found: FirstProperties,
) {
  forEachAssociation(ipma) { item, index ->
    val kind = kinds[index].toInt()
    if (kind != PropertyKind.NONE) found.record(item, primary, kind, index)
  }
}

// Every association an ipma box lists, in order, as the item and the one-based ipco index of its property.
internal inline fun ByteView.forEachAssociation(
  ipma: Box,
  each: (item: Long, index: Int) -> Unit,
) {
  forEachEntry(ipma) { item, first, count ->
    for (at in 0 until count) each(item, associationIndex(ipma, first, at))
    true
  }
}

/**
 * Walks the entries an ipma box lists, in order, handing [each] the item of an entry, where its first association
 * starts and how many associations it holds. The walk stops at the first entry [each] returns false for.
 *
 * The associations are stepped over unread. An `ipma` above version 1, which libheif refuses, throws
 * [MalformedPicture], and so does an entry whose associations run past the end of the box, before any of them is
 * handed to [each].
 */
internal inline fun ByteView.forEachEntry(
  ipma: Box,
  each: (item: Long, first: Int, count: Int) -> Boolean,
) {
  val version = u8(ipma.start)
  if (version > 1) malformed()
  val indexBytes = if (u8(ipma.start + 3) and 1 == 1) 2 else 1
  var remaining = u32(ipma.start + 4)
  var pos = ipma.start + 8
  while (remaining-- > 0) {
    val item = itemId(pos, version)
    pos += if (version == 0) 2 else 4
    val count = u8(pos++)
    if (pos + count * indexBytes > end) malformed()
    if (!each(item, pos, count)) return
    pos += count * indexBytes
  }
}

// The one-based ipco index of association [at] of an entry in [ipma] whose first association starts at [first].
internal fun ByteView.associationIndex(
  ipma: Box,
  first: Int,
  at: Int,
): Int = if (u8(ipma.start + 3) and 1 == 1) u16(first + at * 2) and 0x7FFF else u8(first + at) and 0x7F

/**
 * The kinds of property [FirstProperties] records, each an index into its arrays, with [COUNT] the number of them.
 *
 * [NONE] marks a property of no recorded kind.
 */
internal object PropertyKind {
  const val NONE = -1
  const val PROFILE = 0
  const val NCLX = 1
  const val PIXI = 2
  const val CODEC = 3
  const val COUNT = 4
}
