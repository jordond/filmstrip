package dev.jordond.filmstrip.internal

// Reads where an AVIF or HEIC keeps each item's data, from the iloc box of its meta box.

internal class Extent(
  val offset: Long,
  val offsetAt: Int,
  val length: Long,
  val lengthAt: Int,
)

internal class ItemLocation(
  val item: Long,
  val method: Int,
  val external: Boolean,
  val base: Long,
  val baseAt: Int,
  val extents: List<Extent>,
)

internal class ItemLocations(
  val offsetSize: Int,
  val lengthSize: Int,
  val baseSize: Int,
  val items: List<ItemLocation>,
) {
  private val byItem by lazy {
    val first = HashMap<Long, ItemLocation>(items.size)
    for (location in items) first.getOrPut(location.item) { location }
    first
  }

  // The location of item, the first one listed when item is listed more than once.
  fun location(item: Long): ItemLocation? = byItem[item]
}

// Reads an iloc box of version 0, 1 or 2 whose offset, length, base offset and index fields are 0, 4 or 8 bytes.
internal fun ByteView.itemLocations(iloc: Box): ItemLocations {
  val box = within(iloc.end)
  val version = box.u8(iloc.start)
  if (version > 2) malformed()
  var pos = iloc.start + 4
  val offsetSize = box.u8(pos) ushr 4
  val lengthSize = box.u8(pos) and 0xF
  val baseSize = box.u8(pos + 1) ushr 4
  val indexSize = if (version == 0) 0 else box.u8(pos + 1) and 0xF
  if (listOf(offsetSize, lengthSize, baseSize, indexSize).any { it != 0 && it != 4 && it != 8 }) malformed()
  pos += 2
  val idBytes = if (version < 2) 2 else 4
  val count = box.sized(pos, idBytes)
  pos += idBytes
  val itemBytes = idBytes + (if (version == 0) 0 else 2) + 4 + baseSize
  if (count * itemBytes > iloc.end - pos) malformed()
  val items =
    List(count.toInt()) {
      val item = box.sized(pos, idBytes)
      pos += idBytes
      val method = if (version == 0) 0 else box.u16(pos) and 0xF
      if (version != 0) pos += 2
      val external = box.u16(pos) != 0
      val baseAt = pos + 2
      val base = box.sized(baseAt, baseSize)
      pos = baseAt + baseSize
      val extentCount = box.u16(pos)
      pos += 2
      val perExtent = indexSize + offsetSize + lengthSize
      if ((perExtent == 0 && extentCount > 1) || extentCount.toLong() * perExtent > iloc.end - pos) malformed()
      val extents =
        List(extentCount) {
          pos = box.span(pos, indexSize.toLong())
          val offsetAt = pos
          val lengthAt = pos + offsetSize
          pos = lengthAt + lengthSize
          Extent(box.sized(offsetAt, offsetSize), offsetAt, box.sized(lengthAt, lengthSize), lengthAt)
        }
      ItemLocation(item, method, external, base, baseAt, extents)
    }
  return ItemLocations(offsetSize, lengthSize, baseSize, items)
}

// Where the data of one extent of an item in the file or in idat runs, up to the end of its area when its length is 0.
internal fun ByteView.extentBytes(
  location: ItemLocation,
  extent: Extent,
  idat: Box?,
): Pair<Int, Int> {
  val holder = if (location.method == 0) null else idat ?: malformed()
  val area = holder?.let { within(it.end) } ?: this
  val origin = holder?.start?.toLong() ?: 0L
  if (location.base > area.end || extent.offset > area.end || extent.length > area.end) malformed()
  val start = origin + location.base + extent.offset
  val stop = if (extent.length == 0L) area.end.toLong() else start + extent.length
  if (start > stop || stop > area.end) malformed()
  return start.toInt() to stop.toInt()
}

// Where the first extent of item's data runs, null when locations does not place it in the file or idat.
internal fun ByteView.firstExtent(
  locations: ItemLocations,
  idat: Box?,
  item: Long,
): Pair<Int, Int>? {
  val location = locations.location(item) ?: return null
  if (location.external || location.method > 1) return null
  val extent = location.extents.firstOrNull() ?: return null
  return extentBytes(location, extent, idat)
}

internal fun ByteView.sized(
  at: Int,
  size: Int,
): Long =
  when (size) {
    0 -> 0L
    1 -> u8(at).toLong()
    2 -> u16(at).toLong()
    4 -> u32(at)
    else -> u64(at)
  }

internal val ILOC = fourcc("iloc")
internal val IDAT = fourcc("idat")
