package dev.jordond.filmstrip.picture.fixtures

import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.embeddedColor

internal const val MAX_PROFILE = 16 * 1024 * 1024
internal val PROFILE = sizedProfile(400)
internal val NCLX = byteArrayOf(0, 0, 0, 19) + asciiBytes("colrnclx") + ByteArray(7)
internal val FIRST_HALF = PROFILE.copyOfRange(0, 200)
internal val SECOND_HALF = PROFILE.copyOfRange(200, PROFILE.size)

/**
 * The first ICC profile among the color descriptions [bytes] carry, before any is matched to the stored channels.
 */
internal fun embeddedProfile(bytes: ByteArray): ByteArray? =
  embeddedColor(bytes)?.descriptions?.firstNotNullOfOrNull { (it as? Icc)?.profile }

internal fun sizedProfile(size: Int): ByteArray =
  ByteArray(size) { if (it < 4) (size ushr (24 - it * 8)).toByte() else (it * 7 + it / 3).toByte() }

internal fun bytes(build: Bytes.() -> Unit): ByteArray = Bytes().apply(build).toByteArray()

internal fun asciiBytes(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

internal fun crc32Of(data: ByteArray): Long {
  var crc = 0xFFFFFFFFL
  for (byte in data) {
    crc = crc xor (byte.toLong() and 0xFF)
    repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
  }
  return crc xor 0xFFFFFFFFL
}

internal fun adler32Of(data: ByteArray): Long {
  var a = 1L
  var b = 0L
  for (byte in data) {
    a = (a + (byte.toInt() and 0xFF)) % 65521
    b = (b + a) % 65521
  }
  return (b shl 16) or a
}

/**
 * A one pixel PNG of [colorType] with [beforeImageData] chunks ahead of its `IDAT` and [afterImageData] behind it.
 */
internal fun png(
  vararg beforeImageData: ByteArray,
  afterImageData: ByteArray? = null,
  colorType: Int = 2,
): ByteArray =
  bytes {
    raw(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    pngChunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, colorType.toByte(), 0, 0, 0))
    beforeImageData.forEach { raw(it) }
    pngChunk("IDAT", ByteArray(8))
    afterImageData?.let { raw(it) }
    pngChunk("IEND", ByteArray(0))
  }

internal fun iccp(zlib: ByteArray): ByteArray =
  bytes {
    pngChunk("iCCP", bytes { ascii("ICC Profile\u0000\u0000").raw(zlib) })
  }

internal fun Bytes.pngChunk(
  type: String,
  data: ByteArray,
) {
  val typed = asciiBytes(type) + data
  u32(data.size.toLong())
  raw(typed)
  u32(crc32Of(typed))
}

internal fun storedZlib(
  data: ByteArray,
  splitAt: Int = data.size,
): ByteArray =
  bytes {
    u8(0x78)
    u8(0x01)
    for ((index, block) in listOf(data.copyOfRange(0, splitAt), data.copyOfRange(splitAt, data.size)).withIndex()) {
      u8(if (index == 1) 1 else 0)
      u16(block.size, little = true)
      u16(block.size.inv() and 0xFFFF, little = true)
      raw(block)
    }
    u32(adler32Of(data))
  }

internal fun jpeg(profile: ByteArray): ByteArray =
  jpegSegments(app2(1, 2, profile.copyOfRange(0, 150)), app2(2, 2, profile.copyOfRange(150, profile.size)))

/**
 * A JPEG holding [segments] after its Exif and FlashPix segments, and a baseline frame header of [components] after
 * them when that is set.
 */
internal fun jpegSegments(
  vararg segments: ByteArray,
  components: Int? = null,
): ByteArray =
  bytes {
    u16(0xFFD8)
    u16(0xFFE1)
    u16(2 + 6)
    ascii("Exif\u0000\u0000")
    u16(0xFFE2)
    u16(2 + 5)
    ascii("FPXR\u0000")
    segments.forEach { raw(it) }
    components?.let { count ->
      u16(0xFFC0)
      u16(8 + 3 * count)
      u8(8).u16(1).u16(1).u8(count)
      repeat(count) { u8(it + 1).u8(0x11).u8(0) }
    }
    u16(0xFFDA)
    u16(2)
    u16(0xFFD9)
  }

internal fun app2(
  sequence: Int,
  count: Int,
  chunk: ByteArray,
): ByteArray =
  bytes {
    u16(0xFFE2)
    u16(2 + 14 + chunk.size)
    ascii("ICC_PROFILE\u0000")
    u8(sequence)
    u8(count)
    raw(chunk)
  }

internal fun webp(
  profile: ByteArray?,
  flagged: Boolean = profile != null,
  riffSize: Long? = null,
): ByteArray {
  val chunks =
    bytes {
      webpChunk("VP8X", ByteArray(10).also { if (flagged) it[0] = 0x20 })
      webpChunk("XTRA", ByteArray(3))
      profile?.let { webpChunk("ICCP", it) }
    }
  return bytes {
    ascii("RIFF")
    u32(riffSize ?: (4L + chunks.size), little = true)
    ascii("WEBP")
    raw(chunks)
  }
}

internal fun Bytes.webpChunk(
  type: String,
  data: ByteArray,
) {
  ascii(type)
  u32(data.size.toLong(), little = true)
  raw(data)
  if (data.size % 2 == 1) u8(0)
}

/**
 * An AVIF shaped file with a primary item 1 tagged with [profile] and a second item 2 tagged with [second].
 *
 * Item 2's `colr` is listed first in `ipco`. When [grid] is set item 2 is a tile the primary item is derived from,
 * otherwise it stands for a thumbnail. Version 1 writes 32 bit item ids in `pitm`, `iref` and `ipma`.
 */
internal fun avif(
  profile: ByteArray?,
  second: ByteArray? = null,
  kind: String = "prof",
  wideIndex: Boolean = false,
  grid: Boolean = false,
  trailingAssociations: Boolean = true,
  primaryItem: Boolean = true,
  version: Int = 0,
): ByteArray {
  val nclx = NCLX.copyOf()
  val ispe = fullBox("ispe", 0, 0, ByteArray(8))
  val secondColr = second?.let { colr("prof", it) }
  val primaryColr = profile?.let { colr(kind, it) }
  val properties = listOfNotNull(secondColr, nclx, ispe, primaryColr)

  fun indicesOf(vararg used: ByteArray?) = used.filterNotNull().map { property -> properties.indexOf(property) + 1 }
  val items = listOf(1 to indicesOf(nclx, ispe, primaryColr), 2 to indicesOf(secondColr, ispe))
  return heif(
    properties,
    ipma(items, version, wideIndex),
    primaryItem = primaryItem,
    tiles = if (grid) listOf(2) else null,
    version = version,
    trailingAssociations = trailingAssociations,
  )
}

// An AVIF whose primary item 1 is associated with [associated] out of [count] nclx properties, one of them a profile.
internal fun indexedAvif(
  count: Int,
  profileAt: Int,
  associated: List<Int>,
  wideIndex: Boolean = associated.any { it > 127 },
): ByteArray {
  val profile = colr("prof", PROFILE)
  val properties = List(count) { if (it + 1 == profileAt) profile else NCLX }
  return heif(properties, ipma(listOf(1 to associated), wideIndex = wideIndex))
}

/**
 * A HEIF file whose `meta` holds a `pitm` for item 1, an `iprp` with [properties] and [ipmas], and a `dimg`
 * reference from item 1 to [tiles] when they are set. [fileType] comes first and [itemInfo] follows the `pitm` when it
 * is set.
 *
 * Version 1 writes 32 bit item ids in `pitm` and `iref`. Each `ipma` carries its own version.
 */
internal fun heif(
  properties: List<ByteArray>,
  vararg ipmas: ByteArray,
  primaryItem: Boolean = true,
  tiles: List<Int>? = null,
  version: Int = 0,
  trailingAssociations: Boolean = true,
  metaSize: BoxSize = BoxSize.Exact,
  fileType: ByteArray = ftyp("avif", "mif1"),
  itemInfo: ByteArray? = null,
): ByteArray {
  val ipco = box("ipco", *properties.toTypedArray())
  val iprp = if (trailingAssociations) box("iprp", ipco, *ipmas) else box("iprp", *ipmas, ipco)
  val references =
    tiles?.let { ids ->
      val dimg =
        bytes {
          id(1, version).u16(ids.size)
          ids.forEach { id(it, version) }
        }
      fullBox("iref", version, 0, box("dimg", dimg))
    }
  val metaChildren =
    listOfNotNull(
      fullBox("hdlr", 0, 0, ByteArray(21)),
      if (primaryItem) fullBox("pitm", version, 0, bytes { id(1, version) }) else null,
      itemInfo,
      references,
      iprp,
    )
  return bytes {
    raw(fileType)
    raw(sizedBox("meta", metaSize, bytes { u32(0) }, *metaChildren.toTypedArray()))
  }
}

/**
 * An `ftyp` box with [major] as its major brand, minor version 0 and [compatible] brands, sized as [size] says.
 */
internal fun ftyp(
  major: String,
  vararg compatible: String,
  size: BoxSize = BoxSize.Exact,
): ByteArray =
  sizedBox(
    "ftyp",
    size,
    bytes {
      ascii(major).u32(0)
      compatible.forEach { ascii(it) }
    },
  )

/**
 * An `iinf` box with one `infe` entry of version 2, or version 3 with 32 bit ids when [version] is 1, per item id and
 * its item type in [items].
 */
internal fun iinf(
  vararg items: Pair<Int, String>,
  version: Int = 0,
): ByteArray {
  val entries =
    items.map { (item, type) ->
      fullBox("infe", 2 + version, 0, bytes { id(item, version).u16(0).ascii(type).u8(0) })
    }
  return fullBox("iinf", version, 0, bytes { id(items.size, version) }, *entries.toTypedArray())
}

internal fun ipma(
  items: List<Pair<Int, List<Int>>>,
  version: Int = 0,
  wideIndex: Boolean = false,
): ByteArray = fullBox("ipma", version, if (wideIndex) 1 else 0, associations(items, version, wideIndex))

internal fun associations(
  items: List<Pair<Int, List<Int>>>,
  version: Int,
  wideIndex: Boolean,
): ByteArray =
  bytes {
    u32(items.size.toLong())
    for ((item, indices) in items) {
      id(item, version)
      u8(indices.size)
      indices.forEach { if (wideIndex) u16(0x8000 or it) else u8(0x80 or it) }
    }
  }

internal fun Bytes.id(
  item: Int,
  version: Int,
): Bytes = if (version == 0) u16(item) else u32(item.toLong())

internal fun colr(
  kind: String,
  profile: ByteArray,
): ByteArray = box("colr", bytes { ascii(kind).raw(profile) })

internal fun box(
  type: String,
  vararg body: ByteArray,
): ByteArray = sizedBox(type, BoxSize.Exact, *body)

internal enum class BoxSize { Exact, ToEnd, Large }

internal fun sizedBox(
  type: String,
  size: BoxSize,
  vararg body: ByteArray,
): ByteArray =
  bytes {
    val length = body.sumOf { it.size.toLong() }
    when (size) {
      BoxSize.Exact -> u32(8L + length).ascii(type)
      BoxSize.ToEnd -> u32(0).ascii(type)
      BoxSize.Large -> u32(1).ascii(type).u64(16L + length, little = false)
    }
    body.forEach { raw(it) }
  }

internal fun fullBox(
  type: String,
  version: Int,
  flags: Int,
  vararg body: ByteArray,
): ByteArray = box(type, bytes { u8(version).u8(0).u16(flags) }, *body)

internal fun tiff(
  profile: ByteArray?,
  little: Boolean,
): ByteArray =
  when {
    profile == null -> {
      bytes {
        ascii(if (little) "II" else "MM")
        u16(42, little)
        u32(8, little)
        u16(1, little)
        tiffEntry(256, 3, 1, little).u32(64, little)
        u32(0, little)
      }
    }
    profile.size <= 4 -> {
      bytes {
        ascii(if (little) "II" else "MM")
        u16(42, little)
        u32(8, little)
        u16(2, little)
        tiffEntry(256, 3, 1, little).u32(64, little)
        tiffEntry(34675, 7, profile.size.toLong(), little).raw(profile.copyOf(4))
        u32(0, little)
      }
    }
    else -> {
      tiffHeader(profile.size, little) + profile
    }
  }

// A two entry TIFF whose profile tag points at the bytes straight after this header.
internal fun tiffHeader(
  profileSize: Int,
  little: Boolean,
): ByteArray =
  bytes {
    ascii(if (little) "II" else "MM")
    u16(42, little)
    u32(8, little)
    u16(2, little)
    tiffEntry(256, 3, 1, little).u32(64, little)
    tiffEntry(34675, 7, profileSize.toLong(), little).u32(8L + 2 + 2 * 12 + 4, little)
    u32(0, little)
  }

internal fun Bytes.tiffEntry(
  tag: Int,
  type: Int,
  count: Long,
  little: Boolean,
): Bytes = u16(tag, little).u16(type, little).u32(count, little)

internal fun bigTiff(
  profile: ByteArray,
  little: Boolean,
): ByteArray =
  bytes {
    ascii(if (little) "II" else "MM")
    u16(43, little)
    u16(8, little)
    u16(0, little)
    u64(16, little)
    u64(1, little)
    u16(34675, little)
    u16(1, little)
    u64(profile.size.toLong(), little)
    u64(16L + 8 + 20 + 8, little)
    u64(0, little)
    raw(profile)
  }

internal fun Bytes.u64(
  value: Long,
  little: Boolean,
) {
  val high = value ushr 32
  val low = value and 0xFFFFFFFFL
  if (little) u32(low, little = true).u32(high, little = true) else u32(high).u32(low)
}

internal class Bytes {
  private val out = mutableListOf<Byte>()

  fun u8(value: Int): Bytes = apply { out += value.toByte() }

  fun u16(
    value: Int,
    little: Boolean = false,
  ): Bytes = if (little) u8(value).u8(value shr 8) else u8(value shr 8).u8(value)

  fun u32(
    value: Long,
    little: Boolean = false,
  ): Bytes {
    val high = (value ushr 16).toInt()
    val low = value.toInt()
    return if (little) u16(low, true).u16(high, true) else u16(high).u16(low)
  }

  fun ascii(text: String): Bytes = raw(asciiBytes(text))

  fun raw(data: ByteArray): Bytes = apply { out += data.toList() }

  fun toByteArray(): ByteArray = out.toByteArray()
}
