package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.test.HeifStills.box
import dev.jordond.filmstrip.test.HeifStills.int32
import dev.jordond.filmstrip.test.HeifStills.uInt16

/**
 * Writes `irot` and `imir` properties onto the primary item of a HEIF file a test already has.
 *
 * It walks `meta` to `pitm`, `iloc` and `iprp`, appends the properties to `ipco`, associates them with the primary item
 * in `ipma`, grows every box around them and moves the `iloc` extents that sit past `meta`.
 */
internal object HeifOrientation {
  /**
   * [heif] with an `irot` of [quarterTurns] and an `imir` of [mirrorAxis] on its primary item, as
   * [OrientedPictures.taggedHeif] describes it.
   */
  fun tagged(
    heif: ByteArray,
    quarterTurns: Int?,
    mirrorAxis: Int?,
  ): ByteArray {
    require(quarterTurns != null || mirrorAxis != null) { "A HEIF orientation needs an irot, an imir or both." }
    require(quarterTurns == null || quarterTurns in 0..MAX_QUARTER_TURNS) { "irot takes 0 to 3 quarter turns." }
    require(mirrorAxis == null || mirrorAxis in 0..1) { "imir takes axis 0 or 1." }
    val added =
      listOfNotNull(
        quarterTurns?.let { box("irot", byteArrayOf(it.toByte())) },
        mirrorAxis?.let { box("imir", byteArrayOf(it.toByte())) },
      )
    return withProperties(heif, added, unturned = true)
  }

  /**
   * [heif] with [added] appended to `ipco` in this order and associated as essential at the end of the primary item's
   * `ipma` entry, as [OrientedPictures.heifWithProperties] describes it. When [unturned] is set the primary item may
   * not carry `irot` or `imir` already.
   */
  fun withProperties(
    heif: ByteArray,
    added: List<ByteArray>,
    unturned: Boolean = false,
  ): ByteArray {
    val meta = heif.boxes(0, heif.size).named("meta")
    val metaChildren = heif.boxes(meta.payload + FULL_BOX_HEADER_BYTES, meta.end)
    val iprp = metaChildren.named("iprp")
    val iprpChildren = heif.boxes(iprp.payload, iprp.end)
    val ipco = iprpChildren.named("ipco")
    val properties = heif.boxes(ipco.payload, ipco.end)
    val ipma = heif.ipmaEntry(iprpChildren.named("ipma"), heif.primaryItem(metaChildren.named("pitm")))
    val associated =
      ipma.indices.filter { it != 0 }.map {
        require(it <= properties.size) { "ipma names property $it, but ipco holds ${properties.size}." }
        properties[it - 1].type
      }
    require(!unturned || ("irot" !in associated && "imir" !in associated)) {
      "The primary item already carries irot or imir."
    }

    val addedProperties = added.fold(ByteArray(0)) { bytes, property -> bytes + property }
    val addedAssociations =
      added.indices.fold(ByteArray(0)) { bytes, index ->
        val property = properties.size + 1 + index
        val association = if (ipma.wide) WIDE_ESSENTIAL or property else HeifStills.ESSENTIAL or property
        bytes + if (ipma.wide) uInt16(association) else byteArrayOf(association.toByte())
      }
    require(ipma.wide || properties.size + added.size <= NARROW_INDEX_MAX) { "A narrow ipma indexes 127 properties." }
    val grown = addedProperties.size + addedAssociations.size

    val patched = heif.copyOf()
    patched.grow(meta.start, grown)
    patched.grow(iprp.start, grown)
    patched.grow(ipco.start, addedProperties.size)
    patched.grow(ipma.box.start, addedAssociations.size)
    patched[ipma.countOffset] = (patched[ipma.countOffset] + added.size).toByte()
    patched.moveExtents(metaChildren.named("iloc"), from = meta.end, by = grown)

    val insertions = listOf(ipco.end to addedProperties, ipma.end to addedAssociations).sortedBy { it.first }
    var out = ByteArray(0)
    var from = 0
    for ((at, bytes) in insertions) {
      out += patched.copyOfRange(from, at) + bytes
      from = at
    }
    return out + patched.copyOfRange(from, patched.size)
  }

  private fun ByteArray.primaryItem(pitm: Box): Int {
    val id = pitm.payload + FULL_BOX_HEADER_BYTES
    return if (u8(pitm.payload) == 0) u16(id) else u32(id)
  }

  private fun ByteArray.ipmaEntry(
    ipma: Box,
    item: Int,
  ): IpmaEntry {
    val version = u8(ipma.payload)
    val wide = u8(ipma.payload + 3) and 1 == 1
    var offset = ipma.payload + FULL_BOX_HEADER_BYTES + 4
    repeat(u32(ipma.payload + FULL_BOX_HEADER_BYTES)) {
      val id = if (version < 1) u16(offset) else u32(offset)
      offset += if (version < 1) 2 else 4
      val count = u8(offset)
      val indices =
        List(count) {
          if (wide) u16(offset + 1 + 2 * it) and WIDE_INDEX_MASK else u8(offset + 1 + it) and NARROW_INDEX_MAX
        }
      val end = offset + 1 + count * (if (wide) 2 else 1)
      if (id == item) return IpmaEntry(ipma, wide, offset, end, indices)
      offset = end
    }
    throw IllegalArgumentException("The ipma has no entry for primary item $item.")
  }

  // Moves every iloc extent that a reader finds at or past [from] in the file by [by] bytes, through its base offset
  // when it has one there, and through the extent's own offset otherwise.
  private fun ByteArray.moveExtents(
    iloc: Box,
    from: Int,
    by: Int,
  ) {
    val version = u8(iloc.payload)
    var offset = iloc.payload + FULL_BOX_HEADER_BYTES
    val offsetSize = u8(offset) ushr 4
    val lengthSize = u8(offset) and NIBBLE
    val baseOffsetSize = u8(offset + 1) ushr 4
    val indexSize = if (version == 0) 0 else u8(offset + 1) and NIBBLE
    offset += 2
    val itemCount = if (version < 2) u16(offset) else u32(offset)
    offset += if (version < 2) 2 else 4
    repeat(itemCount) {
      offset += if (version < 2) 2 else 4
      val constructionMethod = if (version == 0) 0 else u16(offset) and NIBBLE
      offset += if (version == 0) 2 else 4
      val baseOffset = offset
      val base = sized(baseOffset, baseOffsetSize)
      val moveBase = constructionMethod == 0 && baseOffsetSize > 0 && base >= from
      if (moveBase) writeSized(baseOffset, baseOffsetSize, base + by)
      offset += baseOffsetSize
      val extentCount = u16(offset)
      offset += 2
      repeat(extentCount) {
        offset += indexSize
        val extent = sized(offset, offsetSize)
        if (constructionMethod == 0 && !moveBase && base + extent >= from) {
          require(offsetSize > 0) { "An iloc extent past meta needs an offset to move." }
          writeSized(offset, offsetSize, extent + by)
        }
        offset += offsetSize + lengthSize
      }
    }
  }

  private fun ByteArray.grow(
    boxStart: Int,
    by: Int,
  ) {
    if (u32(boxStart) == LARGE_SIZE) {
      val sizeAt = boxStart + BOX_HEADER_BYTES
      writeSized(sizeAt, LARGE_SIZE_BYTES, sized(sizeAt, LARGE_SIZE_BYTES) + by)
    } else {
      int32(u32(boxStart) + by).copyInto(this, boxStart)
    }
  }

  private fun ByteArray.sized(
    offset: Int,
    size: Int,
  ): Long = (0 until size).fold(0L) { value, index -> value shl 8 or u8(offset + index).toLong() }

  private fun ByteArray.writeSized(
    offset: Int,
    size: Int,
    value: Long,
  ) {
    for (index in 0 until size) this[offset + index] = (value ushr 8 * (size - 1 - index)).toByte()
  }

  private fun ByteArray.boxes(
    start: Int,
    end: Int,
  ): List<Box> {
    val boxes = mutableListOf<Box>()
    var offset = start
    while (offset + BOX_HEADER_BYTES <= end) {
      val large = u32(offset) == LARGE_SIZE
      val header = if (large) LARGE_BOX_HEADER_BYTES else BOX_HEADER_BYTES
      require(offset + header <= end) { "A box at $offset runs past its parent." }
      val size =
        when {
          large -> sized(offset + BOX_HEADER_BYTES, LARGE_SIZE_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
          u32(offset) == 0 -> end - offset
          else -> u32(offset)
        }
      require(size >= header && offset + size <= end) { "A box at $offset runs past its parent." }
      val type = decodeToString(offset + 4, offset + BOX_HEADER_BYTES)
      boxes += Box(type, offset, offset + header, offset + size)
      offset += size
    }
    return boxes
  }

  private fun List<Box>.named(type: String): Box =
    singleOrNull { it.type == type } ?: throw IllegalArgumentException("A HEIF needs one $type box.")

  // A box's type, where its header starts, where its payload starts and where it ends.
  private class Box(
    val type: String,
    val start: Int,
    val payload: Int,
    val end: Int,
  )

  // The primary item's ipma entry: where its association count sits, where its associations end and the property
  // indices they name.
  private class IpmaEntry(
    val box: Box,
    val wide: Boolean,
    val countOffset: Int,
    val end: Int,
    val indices: List<Int>,
  )

  private const val BOX_HEADER_BYTES = 8

  // A box whose four byte size is 1 carries its size in the eight bytes after its type, as ImageIO writes mdat.
  private const val LARGE_SIZE = 1
  private const val LARGE_SIZE_BYTES = 8
  private const val LARGE_BOX_HEADER_BYTES = BOX_HEADER_BYTES + LARGE_SIZE_BYTES
  private const val FULL_BOX_HEADER_BYTES = 4
  private const val MAX_QUARTER_TURNS = 3
  private const val WIDE_ESSENTIAL = 0x8000
  private const val NARROW_INDEX_MAX = 0x7F
  private const val WIDE_INDEX_MASK = 0x7FFF

  private const val NIBBLE = 0x0F
}

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and BYTE

internal fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.u32(offset: Int): Int = (u16(offset) shl 16) or u16(offset + 2)

private const val BYTE = 0xFF
