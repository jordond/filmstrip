package dev.jordond.filmstrip.internal

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
  val version = u8(ipma.start)
  val wideIndex = u8(ipma.start + 3) and 1 == 1
  var remaining = u32(ipma.start + 4)
  var pos = ipma.start + 8
  while (remaining-- > 0) {
    val item = itemId(pos, version)
    pos += if (version == 0) 2 else 4
    repeat(u8(pos++)) {
      val index = if (wideIndex) u16(pos) and 0x7FFF else u8(pos) and 0x7F
      pos += if (wideIndex) 2 else 1
      each(item, index)
    }
  }
}

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
