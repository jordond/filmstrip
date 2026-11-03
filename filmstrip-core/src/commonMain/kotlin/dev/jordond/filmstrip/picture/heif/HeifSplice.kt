package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.fourcc

// Splices bytes into an AVIF or HEIC and moves every box size and item location the splices shift, then checks the
// result against the file it came from.

// Replaces the bytes from start up to end with replacement.
internal class Splice(
  val start: Int,
  val end: Int,
  val replacement: ByteArray,
) {
  val delta: Int get() = replacement.size - (end - start)
}

// A big endian field of width bytes at a position in the original file, given a new value.
internal class Field(
  val at: Int,
  val width: Int,
  val value: Long,
)

// The top-level boxes up to the first one that does not parse, past which a reader ignores the file, the first limit of
// them.
internal fun ByteView.leadingBoxes(limit: Int = Int.MAX_VALUE): List<Box> = parsing(0, end).take(limit).toList()

// A file with its splices made, checked against the file it came from before it is handed back.
internal class Rewritten(
  val bytes: ByteArray,
  val splices: List<Splice>,
) {
  val file = ByteView(bytes, bytes.size)

  // The boxes of the file's meta box, the item locations and idat among them.
  private val parts by lazy { file.metaParts() }

  // Where a byte at position in the input sits once the splices before it are made.
  fun moved(position: Int): Int = position + splices.filter { it.end <= position }.sumOf { it.delta }

  // Whether the top-level boxes walk exactly to where the input's end up, of the same types in the same order.
  fun keepsBoxes(top: List<Box>): Boolean {
    val covered = moved(top.last().end)
    val boxes = file.within(covered).boxes(0, covered).toList()
    return boxes.map { it.type } == top.map { it.type } && boxes.last().end == covered
  }

  // Whether every item lists the extents it did and each holds the input's bytes with the splices inside it made.
  fun keepsItemData(
    input: ByteView,
    locations: ItemLocations?,
    idat: Box?,
  ): Boolean {
    if (locations == null) return true
    val now = parts?.locations ?: return false
    val nowIdat = parts?.idat
    if (now.items.size != locations.items.size) return false
    return locations.items.zip(now.items).all { (before, after) ->
      val same =
        before.item == after.item && before.method == after.method && before.external == after.external &&
          before.extents.size == after.extents.size
      same && (before.external || before.method > 1 || keepsExtents(input, before, idat, after, nowIdat))
    }
  }

  private fun keepsExtents(
    input: ByteView,
    before: ItemLocation,
    idat: Box?,
    after: ItemLocation,
    nowIdat: Box?,
  ): Boolean =
    before.extents.indices.all { index ->
      val (from, to) = input.extentBytes(before, before.extents[index], idat)
      val (nowFrom, nowTo) = file.extentBytes(after, after.extents[index], nowIdat)
      val expected = input.bytes.splicing(from, to, splices)
      nowTo - nowFrom == expected.size && expected.indices.all { bytes[nowFrom + it] == expected[it] }
    }
}

// Grows or shrinks each box holding a splice by the splices inside it, descending into meta, iprp and ipco.
internal fun ByteView.sizeFields(
  boxes: List<Box>,
  splices: List<Splice>,
  fields: MutableList<Field>,
) {
  for (box in boxes) {
    val inside = splices.filter { it.start >= box.start && it.end <= box.end }
    if (inside.isEmpty()) continue
    val delta = inside.sumOf { it.delta.toLong() }
    when (val size = u32(box.at)) {
      0L -> Unit
      1L -> fields += Field(box.at + 8, 8, u64(box.at + 8) + delta)
      else -> fields += Field(box.at, 4, size + delta)
    }
    val childStart =
      when (box.type) {
        META -> box.start + 4
        IPRP, IPCO -> box.start
        else -> continue
      }
    within(box.end).sizeFields(boxes(childStart, box.end).toList(), inside, fields)
  }
}

// The new base offsets, extent offsets and extent lengths of every item whose data the splices move or resize.
internal fun ItemLocations.fields(
  splices: List<Splice>,
  idat: Box?,
): List<Field>? {
  fun moved(position: Long): Long = position + splices.filter { it.end <= position }.sumOf { it.delta.toLong() }

  fun cut(position: Long): Boolean = splices.any { it.start < position && position < it.end }
  val fields = mutableListOf<Field>()
  for (location in items) {
    if (location.external || location.method > 1) continue
    val origin = if (location.method == 0) 0L else (idat ?: return null).start.toLong()
    val start = origin + location.base
    val base = moved(start) - moved(origin)
    if (base != location.base) fields += Field(location.baseAt, baseSize, base)
    for (extent in location.extents) {
      val from = start + extent.offset
      val to = from + extent.length
      if (cut(from) || (extent.length != 0L && cut(to))) return null
      val offset = moved(from) - moved(start)
      val length = if (extent.length == 0L) 0L else moved(to) - moved(from)
      if (offset != extent.offset) fields += Field(extent.offsetAt, offsetSize, offset)
      if (length != extent.length) fields += Field(extent.lengthAt, lengthSize, length)
    }
  }
  return fields
}

// The file with every field written and every splice made, or null when a field does not fit its width.
internal fun spliced(
  bytes: ByteArray,
  fields: List<Field>,
  splices: List<Splice>,
): ByteArray? {
  val patched = bytes.copyOf()
  for (field in fields) {
    val fits = field.value >= 0 && (field.width == 8 || (field.width == 4 && field.value <= MAX_U32))
    if (!fits || splices.any { field.at < it.end && field.at + field.width > it.start }) return null
    repeat(field.width) { patched[field.at + it] = (field.value ushr (8 * (field.width - 1 - it))).toByte() }
  }
  return patched.splicing(0, patched.size, splices)
}

// The bytes from from up to to with each splice that sits inside them made.
private fun ByteArray.splicing(
  from: Int,
  to: Int,
  splices: List<Splice>,
): ByteArray {
  val inside = splices.filter { it.start >= from && it.end <= to }
  val out = ByteArray(to - from + inside.sumOf { it.delta })
  var read = from
  var written = 0
  for (splice in inside) {
    copyInto(out, written, read, splice.start)
    written += splice.start - read
    splice.replacement.copyInto(out, written)
    written += splice.replacement.size
    read = splice.end
  }
  copyInto(out, written, read, to)
  return out
}

internal val MOOV = fourcc("moov")

private const val MAX_U32 = 0xFFFFFFFFL
