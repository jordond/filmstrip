package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed

// Walks the ISOBMFF boxes of an AVIF or HEIC and reads the item ids, item types, references and tiles its `meta` box
// lists.

/**
 * An ISOBMFF box read out of a container. Its size field sits at [at], followed by [type], and its payload runs from
 * [start], past a 64-bit size when it has one, up to [end].
 */
internal class Box(
  val type: Int,
  val start: Int,
  val end: Int,
  val at: Int,
)

internal fun ByteView.boxes(
  start: Int,
  end: Int,
): Sequence<Box> =
  sequence {
    val view = within(end)
    var pos = start
    while (pos < end) {
      val size = view.u32(pos)
      val header = if (size == 1L) 16 else 8
      val length =
        when (size) {
          0L -> (end - pos).toLong()
          1L -> view.u64(pos + 8)
          else -> size
        }
      if (length < header) malformed()
      val boxEnd = view.span(pos, length)
      yield(Box(view.u32(pos + 4).toInt(), pos + header, boxEnd, pos))
      pos = boxEnd
    }
  }

internal fun ByteView.itemId(
  at: Int,
  version: Int,
): Long = if (version == 0) u16(at).toLong() else u32(at)

// The item type of each item an iinf box lists. An infe of version 0 or 1 carries no item type and is skipped.
internal fun ByteView.itemTypes(iinf: Box): Map<Long, Int> {
  val countSize = if (u8(iinf.start) == 0) 2 else 4
  val types = mutableMapOf<Long, Int>()
  for (infe in boxes(iinf.start + 4 + countSize, iinf.end)) {
    if (infe.type != INFE) continue
    val entry = within(infe.end)
    val version = entry.u8(infe.start)
    if (version < 2) continue
    val idSize = if (version == 2) 2 else 4
    val item = entry.itemId(infe.start + 4, if (version == 2) 0 else 1)
    types.getOrPut(item) { entry.u32(infe.start + 4 + idSize + 2).toInt() }
  }
  return types
}

internal fun codecOf(itemType: Int?): CodecKind =
  when (itemType) {
    HVC1, HEV1 -> CodecKind.Hevc
    AV01 -> CodecKind.Av1
    else -> CodecKind.Other
  }

// The items the dimg reference of primary in iref lists, or null when it has none.
internal fun ByteView.tilesOf(
  primary: Long,
  iref: Box,
): LongArray? = references(iref).firstOrNull { it.type == DIMG && it.from == primary }?.to

/**
 * One single item reference of an `iref` box, of [type] from item [from] to the items [to] lists in order.
 */
internal class Reference(
  val type: Int,
  val from: Long,
  val to: LongArray,
)

// Every single item reference iref lists, in order, each read as the walk reaches it.
internal fun ByteView.references(iref: Box): Sequence<Reference> {
  val version = u8(iref.start)
  val idSize = if (version == 0) 2 else 4
  return boxes(iref.start + 4, iref.end).map { reference ->
    val box = within(reference.end)
    val from = box.itemId(reference.start, version)
    val count = box.u16(reference.start + idSize)
    box.span(reference.start + idSize + 2, count.toLong() * idSize)
    val to = LongArray(count) { box.itemId(reference.start + idSize + 2 + it * idSize, version) }
    Reference(reference.type, from, to)
  }
}

private val INFE = fourcc("infe")
private val HVC1 = fourcc("hvc1")
private val HEV1 = fourcc("hev1")
private val AV01 = fourcc("av01")
private val DIMG = fourcc("dimg")
