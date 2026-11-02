package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.test.HeifStills.box
import dev.jordond.filmstrip.test.HeifStills.fullBox
import dev.jordond.filmstrip.test.HeifStills.int32
import dev.jordond.filmstrip.test.HeifStills.uInt16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `irot` and `imir` properties [OrientedPictures.taggedHeif] writes, read back from the boxes of each file.
 *
 * It tags the stills [HeifStills] writes and files laid out by hand with the box versions, wide associations, base
 * offsets and `idat` items those stills never use. Each tagged file is checked to carry the new properties on its
 * primary item alone, and every item's data to still sit where `iloc` says.
 */
class OrientedHeifTest {
  @Test
  fun taggedHeifAppendsIrotAndImirOnThePrimaryItem() {
    for ((name, heif) in heifs()) assertEveryTag(name, heif)
  }

  @Test
  fun taggedHeifFollowsEveryIpmaAndIlocLayout() {
    for (layout in LAYOUTS) assertEveryTag("$layout", layout.bytes())
  }

  @Test
  fun taggedHeifSkipsAnAssociationWithNoProperty() {
    assertEveryTag("index 0", HeifLayout(primaryIndices = listOf(0, 1, 2, 3)).bytes())
  }

  @Test
  fun taggedHeifRefusesAnAssociationPastIpco() {
    val heif = HeifLayout(primaryIndices = listOf(1, 2, 3, 4)).bytes()

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, 1, null) }
  }

  @Test
  fun taggedHeifRefusesAPrimaryItemAlreadyOriented() {
    val tagged = OrientedPictures.taggedHeif(ColourPictures.HEIC_NCLX_BT709_FULL.bytes(), 1, null)

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(tagged, null, 0) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(tagged, 2, null) }
  }

  @Test
  fun taggedHeifRefusesAnOrientationOutOfRange() {
    val heif = HeifLayout().bytes()

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, null, null) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, 4, null) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, -1, null) }
    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, null, 2) }
  }

  @Test
  fun taggedHeifRefusesAFileWithoutMeta() {
    val heif = box("ftyp", "heic".encodeToByteArray() + int32(0)) + box("mdat", ByteArray(4))

    assertFailsWith<IllegalArgumentException> { OrientedPictures.taggedHeif(heif, 1, 0) }
  }

  private fun assertEveryTag(
    name: String,
    heif: ByteArray,
  ) {
    val before = heifProperties(heif)
    val essential = if (before.wide) WIDE_ESSENTIAL else HeifStills.ESSENTIAL
    for (quarterTurns in listOf(null, 0, 1, 2, 3)) {
      for (mirrorAxis in listOf(null, 0, 1)) {
        if (quarterTurns == null && mirrorAxis == null) continue
        val case = "$name irot $quarterTurns imir $mirrorAxis"
        val tagged = OrientedPictures.taggedHeif(heif, quarterTurns, mirrorAxis)
        val after = heifProperties(tagged)
        val added = listOfNotNull(quarterTurns?.let { "irot" to it }, mirrorAxis?.let { "imir" to it })

        assertEquals(before.types + added.map { it.first }, after.types, "$case ipco")
        assertEquals(added.map { it.second }, after.payloads.takeLast(added.size), "$case values")
        assertEquals(
          before.associations.mapValues { (item, indices) ->
            if (item != before.primary) {
              indices
            } else {
              indices + added.indices.map { essential or (before.types.size + 1 + it) }
            }
          },
          after.associations,
          "$case ipma",
        )
        assertEquals(itemData(heif), itemData(tagged), "$case item data")
      }
    }
  }

  private fun heifs(): List<Pair<String, ByteArray>> {
    val heic = ColourPictures.HEIC_NCLX_BT709_FULL
    val avif = ColourPictures.AVIFS.first()
    val item = avif.thumbnail()
    val alpha = AlphaPlanes.avif()
    val withAlpha =
      HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, alpha = alpha)
    return listOf(
      "$heic" to heic.bytes(),
      "$avif" to avif.bytes(),
      "$heic with a thumbnail" to heic.bytes(thumbnail = heic.thumbnail()),
      "$heic grid" to heic.gridBytes(),
      "$avif grid with a thumbnail" to avif.gridBytes(thumbnail = avif.thumbnail()),
      "$avif with alpha" to withAlpha,
    )
  }

  private class HeifProperties(
    val primary: Int,
    val wide: Boolean,
    val types: List<String>,
    val payloads: List<Int>,
    val associations: Map<Int, List<Int>>,
  )

  // The ipco properties and ipma associations of [heif], each association as stored, its essential bit included.
  private fun heifProperties(heif: ByteArray): HeifProperties {
    val children = heif.childrenOf(heifBoxes(heif).named("meta"), fullBox = true)
    val iprp = heif.childrenOf(children.named("iprp"))
    val properties = heif.childrenOf(iprp.named("ipco"))
    val ipma = iprp.named("ipma")
    val version = heif.uInt8At(ipma.start)
    val wide = heif.uInt8At(ipma.start + 3) and 1 == 1
    val associations = mutableMapOf<Int, List<Int>>()
    var offset = ipma.start + 8
    repeat(heif.int32At(ipma.start + 4)) {
      val item = if (version == 0) heif.uInt16At(offset) else heif.int32At(offset)
      offset += if (version == 0) 2 else 4
      val count = heif.uInt8At(offset)
      associations[item] =
        List(count) { if (wide) heif.uInt16At(offset + 1 + 2 * it) else heif.uInt8At(offset + 1 + it) }
      offset += 1 + count * (if (wide) 2 else 1)
    }
    assertEquals(ipma.end, offset, "ipma entries fill the box")
    val pitm = children.named("pitm")
    return HeifProperties(
      primary = if (heif.uInt8At(pitm.start) == 0) heif.uInt16At(pitm.start + 4) else heif.int32At(pitm.start + 4),
      wide = wide,
      types = properties.map { it.type },
      payloads = properties.map { if (it.end - it.start == 1) heif.uInt8At(it.start) else -1 },
      associations = associations,
    )
  }

  // Each item's data as iloc locates it, every extent joined, from the file or from idat by construction method.
  private fun itemData(heif: ByteArray): Map<Int, List<Byte>> {
    val children = heif.childrenOf(heifBoxes(heif).named("meta"), fullBox = true)
    val iloc = children.named("iloc")
    val version = heif.uInt8At(iloc.start)
    var offset = iloc.start + 4

    fun read(size: Int): Long =
      (0 until size).fold(0L) { value, it -> value shl 8 or heif.uInt8At(offset + it).toLong() }.also { offset += size }

    val offsetSize = heif.uInt8At(offset) ushr 4
    val lengthSize = heif.uInt8At(offset) and 0x0F
    val baseOffsetSize = heif.uInt8At(offset + 1) ushr 4
    val indexSize = if (version == 0) 0 else heif.uInt8At(offset + 1) and 0x0F
    offset += 2
    val data =
      List(read(if (version < 2) 2 else 4).toInt()) {
        val item = read(if (version < 2) 2 else 4).toInt()
        val origin = if (version > 0 && read(2).toInt() and 0x0F == 1) children.named("idat").start else 0
        read(2)
        val base = read(baseOffsetSize)
        val extents =
          List(read(2).toInt()) {
            read(indexSize)
            val start = origin + (base + read(offsetSize)).toInt()
            heif.copyOfRange(start, start + read(lengthSize).toInt()).toList()
          }
        item to extents.flatten()
      }
    assertEquals(iloc.end, offset, "iloc items fill the box")
    return data.toMap()
  }

  // A HEIF file laid out by hand. The primary item's data sits in mdat in two extents, and a second item's sits in
  // idat when [idat] is set and after the primary item's otherwise. With [baseOffsetSize] set, each mdat item's base
  // offset is where mdat's payload starts when [baseInMdat] is set and 0 otherwise. An ipma version 1 with an iloc
  // version 2 gives both items ids past 16 bits. With [largeMdat] set, mdat carries its size in eight bytes after its
  // type, as ImageIO writes it.
  private data class HeifLayout(
    val ipmaVersion: Int = 0,
    val wide: Boolean = false,
    val ilocVersion: Int = 0,
    val offsetSize: Int = 4,
    val baseOffsetSize: Int = 0,
    val baseInMdat: Boolean = true,
    val indexSize: Int = 0,
    val idat: Boolean = false,
    val ipmaFirst: Boolean = false,
    val primaryIndices: List<Int> = listOf(1, 2, 3),
    val largeMdat: Boolean = false,
  ) {
    fun bytes(): ByteArray {
      val bigIds = ipmaVersion == 1 && ilocVersion == 2
      val primary = if (bigIds) 0x10001 else 1
      val second = primary + 1
      val properties =
        fullBox("ispe", int32(8) + int32(8)) + fullBox("pixi", byteArrayOf(3, 8, 8, 8)) + box("hvcC", ByteArray(23))
      val ipma =
        fullBox(
          "ipma",
          int32(2) + entry(primary, primaryIndices) + entry(second, listOf(1, 2)),
          version = ipmaVersion,
          flags = if (wide) 1 else 0,
        )
      val ipco = box("ipco", properties)
      val iprp = box("iprp", if (ipmaFirst) ipma + ipco else ipco + ipma)
      val idId = if (bigIds) 4 else 2
      val infes =
        listOf(primary to "hvc1", second to "Exif").fold(ByteArray(0)) { bytes, (item, type) ->
          val infe = sized(item.toLong(), idId) + uInt16(0) + type.encodeToByteArray() + byteArrayOf(0)
          bytes + fullBox("infe", infe, version = if (bigIds) 3 else 2)
        }
      val iinf = fullBox("iinf", uInt16(2) + infes)
      val ftyp = box("ftyp", "heic".encodeToByteArray() + int32(0) + "mif1heic".encodeToByteArray())

      fun meta(dataOffset: Int): ByteArray {
        val base = if (baseOffsetSize > 0 && baseInMdat) dataOffset.toLong() else 0L
        val start = dataOffset - base
        val primaryExtents = listOf(start to SPLIT, start + SPLIT to PRIMARY_DATA.size - SPLIT)
        val secondExtents = listOf((if (idat) 0L else start + PRIMARY_DATA.size) to SECOND_DATA.size)
        val iloc =
          fullBox(
            "iloc",
            byteArrayOf((offsetSize shl 4 or 4).toByte(), (baseOffsetSize shl 4 or indexSize).toByte()) +
              sized(2, if (ilocVersion < 2) 2 else 4) +
              location(primary, 0, base, primaryExtents) +
              location(second, if (idat) 1 else 0, if (idat) 0 else base, secondExtents),
            version = ilocVersion,
          )
        val pitm = fullBox("pitm", sized(primary.toLong(), idId), version = if (bigIds) 1 else 0)
        val hdlr = fullBox("hdlr", ByteArray(4) + "pict".encodeToByteArray() + ByteArray(13))
        return fullBox("meta", hdlr + pitm + iloc + iinf + iprp + if (idat) box("idat", SECOND_DATA) else ByteArray(0))
      }

      val mdat = PRIMARY_DATA + if (idat) ByteArray(0) else SECOND_DATA
      val mdatHeader =
        if (largeMdat) {
          int32(1) + "mdat".encodeToByteArray() + sized(16L + mdat.size, 8)
        } else {
          int32(8 + mdat.size) + "mdat".encodeToByteArray()
        }
      val dataOffset = ftyp.size + meta(0).size + mdatHeader.size
      return ftyp + meta(dataOffset) + mdatHeader + mdat
    }

    private fun entry(
      item: Int,
      indices: List<Int>,
    ): ByteArray {
      val essential = if (wide) WIDE_ESSENTIAL else HeifStills.ESSENTIAL
      val associations = indices.map { if (it == CONFIGURATION) essential or it else it }
      return sized(item.toLong(), if (ipmaVersion == 0) 2 else 4) + byteArrayOf(indices.size.toByte()) +
        associations.fold(ByteArray(0)) { bytes, it -> bytes + sized(it.toLong(), if (wide) 2 else 1) }
    }

    private fun location(
      item: Int,
      constructionMethod: Int,
      base: Long,
      extents: List<Pair<Long, Int>>,
    ): ByteArray =
      sized(item.toLong(), if (ilocVersion < 2) 2 else 4) +
        (if (ilocVersion > 0) uInt16(constructionMethod) else ByteArray(0)) +
        uInt16(0) + sized(base, baseOffsetSize) + uInt16(extents.size) +
        extents.fold(ByteArray(0)) { bytes, (at, length) ->
          bytes + sized(0, indexSize) + sized(at, offsetSize) + int32(length)
        }

    private fun sized(
      value: Long,
      size: Int,
    ): ByteArray = ByteArray(size) { (value ushr 8 * (size - 1 - it)).toByte() }
  }

  private companion object {
    const val WIDE_ESSENTIAL = 0x8000
    const val CONFIGURATION = 3
    const val SPLIT = 5
    val PRIMARY_DATA = ByteArray(12) { (0x40 + it).toByte() }
    val SECOND_DATA = ByteArray(6) { (0x60 + it).toByte() }

    val LAYOUTS =
      listOf(
        HeifLayout(),
        HeifLayout(wide = true),
        HeifLayout(ipmaVersion = 1),
        HeifLayout(ipmaVersion = 1, wide = true, ilocVersion = 2),
        HeifLayout(ipmaFirst = true),
        HeifLayout(wide = true, ipmaFirst = true),
        HeifLayout(baseOffsetSize = 4),
        HeifLayout(baseOffsetSize = 8, offsetSize = 8),
        HeifLayout(baseOffsetSize = 4, baseInMdat = false),
        HeifLayout(ilocVersion = 1),
        HeifLayout(ilocVersion = 1, idat = true),
        HeifLayout(ilocVersion = 2, baseOffsetSize = 4, indexSize = 4, idat = true),
        HeifLayout(largeMdat = true),
        HeifLayout(baseOffsetSize = 4, largeMdat = true),
      )
  }
}
