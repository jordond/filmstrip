package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.Icc
import dev.jordond.filmstrip.media.PictureColour

// Builders for the colour descriptions besides ICC that PNG and HEIF carry, and for the stored channel counts each
// container records.

internal fun cicpChunk(
  primaries: Int,
  transfer: Int,
  matrix: Int = 0,
  fullRange: Int = 1,
): ByteArray =
  bytes { pngChunk("cICP", byteArrayOf(primaries.toByte(), transfer.toByte(), matrix.toByte(), fullRange.toByte())) }

internal fun srgbChunk(intent: Int = 0): ByteArray = bytes { pngChunk("sRGB", byteArrayOf(intent.toByte())) }

internal fun gamaChunk(gamma: Long): ByteArray = bytes { pngChunk("gAMA", bytes { u32(gamma) }) }

internal fun chrmChunk(values: IntArray): ByteArray =
  bytes { pngChunk("cHRM", bytes { values.forEach { u32(it.toLong()) } }) }

internal fun iccpChunk(profile: ByteArray): ByteArray = iccp(storedZlib(profile))

/**
 * [chunk] with the last byte of its CRC changed, so a reader that checks it skips the chunk.
 */
internal fun badCrc(chunk: ByteArray): ByteArray = chunk.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

// White, red, green and blue, x then y, times 100000 as cHRM stores them.
internal val REC709_CHRM = intArrayOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)
internal val P3_CHRM = intArrayOf(31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000)
internal val BT2020_CHRM = intArrayOf(31270, 32900, 70800, 29200, 17000, 79700, 13100, 4600)

internal fun nclx(
  primaries: Int,
  transfer: Int,
  matrix: Int = 6,
  fullRange: Boolean = true,
): ByteArray =
  box(
    "colr",
    bytes {
      ascii("nclx")
        .u16(primaries)
        .u16(transfer)
        .u16(matrix)
        .u8(if (fullRange) 0x80 else 0)
    },
  )

internal fun pixi(channels: Int): ByteArray =
  fullBox("pixi", 0, 0, bytes { u8(channels).raw(ByteArray(channels) { 8 }) })

internal fun av1c(monochrome: Boolean): ByteArray =
  box("av1C", byteArrayOf(0x81.toByte(), 0x0D, if (monochrome) 0x1C else 0x0C, 0))

internal fun hvcc(chromaFormat: Int): ByteArray =
  box("hvcC", ByteArray(23).also { it[16] = (0xFC or chromaFormat).toByte() })

/**
 * A HEIF whose primary item 1 holds [primary] properties, derived from tile 2 holding [tile] properties when that is
 * set, after [fileType] and with [itemInfo] in its `meta` when that is set.
 */
internal fun heifItems(
  primary: List<ByteArray>,
  tile: List<ByteArray>? = null,
  fileType: ByteArray = ftyp("avif", "mif1"),
  itemInfo: ByteArray? = null,
): ByteArray {
  val properties = primary + tile.orEmpty()
  val tileItem = tile?.let { 2 to it.indices.map { at -> primary.size + at + 1 } }
  val items = listOfNotNull(1 to primary.indices.map { it + 1 }, tileItem)
  return heif(properties, ipma(items), tiles = tile?.let { listOf(2) }, fileType = fileType, itemInfo = itemInfo)
}

/**
 * A TIFF with one directory of [entries], each a tag, a field type and a value held in the entry, and a profile tag
 * pointing at [profile] after the directory when it is set.
 *
 * Classic little endian unless [little] or [big] say otherwise. A SHORT value sits left justified in its entry's value
 * field, at the field's start in either byte order, and BigTIFF's value field starts 12 bytes into a 20 byte entry.
 */
internal fun tiffWith(
  profile: ByteArray?,
  vararg entries: Triple<Int, Int, Int>,
  little: Boolean = true,
  big: Boolean = false,
): ByteArray {
  val count = entries.size + if (profile != null) 1 else 0
  val profileAt = if (big) 16L + 8 + count * 20 + 8 else 8L + 2 + count * 12 + 4
  val field = if (big) 8 else 4
  return bytes {
    ascii(if (little) "II" else "MM")
    if (big) {
      u16(43, little).u16(8, little).u16(0, little)
      u64(16, little)
      u64(count.toLong(), little)
    } else {
      u16(42, little).u32(8, little).u16(count, little)
    }
    entries.forEach { (tag, type, value) ->
      u16(tag, little).u16(type, little)
      if (big) u64(1, little) else u32(1, little)
      val width = if (type == 3) 2 else 4
      if (width == 2) u16(value, little) else u32(value.toLong(), little)
      raw(ByteArray(field - width))
    }
    profile?.let {
      u16(34675, little).u16(7, little)
      if (big) {
        u64(it.size.toLong(), little)
        u64(profileAt, little)
      } else {
        u32(it.size.toLong(), little).u32(profileAt, little)
      }
    }
    if (big) u64(0, little) else u32(0, little)
    profile?.let { raw(it) }
  }
}

internal fun samplesPerPixel(
  count: Int,
  type: Int = 3,
) = Triple(277, type, count)

internal fun extraSamples() = Triple(338, 3, 2)

internal fun photometric(value: Int) = Triple(262, 3, value)

/**
 * A description reduced to something equality reads, a profile's bytes as a list and any other description as it is.
 */
internal fun PictureColour?.summary(): Any? = if (this is Icc) profile.toList() else this
