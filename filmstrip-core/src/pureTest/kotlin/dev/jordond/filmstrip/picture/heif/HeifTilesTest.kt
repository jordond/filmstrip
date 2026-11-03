package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.ChromaFormat
import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.ftyp
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.iinf
import dev.jordond.filmstrip.picture.fixtures.ipma
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Rewrites and reads hand-built grids whose third tile carries units of its own, so a walk that stops at the first
 * tile fails, and walks a grid of thousands of tiles visiting each item location once.
 */
class HeifTilesTest {
  private val bt709Full = Cicp(1, 13, 1, true)
  private val target = CodestreamSignal(1, 13, 1, true)
  private val silent = SpsShape().nal()
  private val second = SpsShape(spsId = 1).nal()

  private fun stating(nal: ByteArray): ByteArray = spsStating(nal, target)!!

  // The signal of each unit the walk finds in the data of item.
  private fun signalsIn(
    file: ByteArray,
    item: Long,
  ): List<CodestreamSignal?> {
    val view = ByteView(file, file.size)
    val coded = heifCodedItems(view) ?: error("no coded items")
    val locations = coded.locations ?: error("no iloc")
    val idat = coded.idat
    val (start, stop) = view.firstExtent(locations, idat, item) ?: error("item $item has no data")
    return view.codestreamUnits(coded.codec, coded.items, locations, idat).filter { it.start in start..<stop }.map {
      view.signalOf(it)
    }
  }

  @Test
  fun `a parameter set in band in the third tile alone is rewritten and reads back`() {
    val file = TestHeic(listOf(silent), bt709Full, grid = true, tiles = mapOf(4 to TestTile(inBand = second)))
    val rewritten = heifWithExplicitCodestreamColor(file.bytes()).handed
    val stated = mapOf(4 to TestTile(inBand = stating(second)))
    rewritten shouldBe TestHeic(listOf(stating(silent)), grid = true, tiles = stated).bytes()
    signalsIn(rewritten, 4) shouldBe listOf(target)
    signalsIn(rewritten, 3) shouldBe emptyList()
  }

  @Test
  fun `the third tile's own hvcC and its parameter set in band are rewritten`() {
    val own = TestTile(hvccOf(VPS_NAL, second, PPS_NAL), inBand = second)
    val file = TestHeic(listOf(silent), grid = true, tiles = mapOf(4 to own))
    val rewritten = heifWithExplicitCodestreamColor(file.bytes()).handed
    val stated = TestTile(hvccOf(VPS_NAL, stating(second), PPS_NAL), inBand = stating(second))
    rewritten shouldBe TestHeic(listOf(stating(silent)), grid = true, tiles = mapOf(4 to stated)).bytes()
    signalsIn(rewritten, 4) shouldBe listOf(target)
  }

  @Test
  fun `a sequence header in band in the third tile is rewritten and reads back`() {
    val silentHeader = SequenceShape()
    val third = SequenceShape(description = Triple(9, 16, 9))
    val avif = { shared: SequenceShape, own: SequenceShape ->
      TestHeic(
        emptyList(),
        bt709Full,
        grid = true,
        itemType = "av01",
        configuration = box("av1C", byteArrayOf(0x81.toByte(), 0x0D, 0x0C, 0)),
        coded = shared.obu() + STAND_IN_FRAME,
        tiles = mapOf(4 to TestTile(coded = own.obu() + STAND_IN_FRAME)),
      ).bytes()
    }
    val rewritten = heifWithExplicitCodestreamColor(avif(silentHeader, third)).handed
    rewritten shouldBe avif(silentHeader.stating(target), third.stating(target))
    signalsIn(rewritten, 4) shouldBe listOf(target)
    signalsIn(rewritten, 2) shouldBe listOf(target)
  }

  @Test
  fun `a grid of thousands of tiles visits each item location once and reads its depth and layout`() {
    val file = manyTiles(MANY_TILES, firstInBand = SpsShape(chromaFormat = 3).nal())
    val view = ByteView(file, file.size)
    val coded = heifCodedItems(view) ?: error("no coded items")
    coded.codec shouldBe CodecKind.Hevc
    val parsed = coded.locations ?: error("no iloc")
    val idat = coded.idat
    val items = Counted(parsed.items)
    val counted = ItemLocations(parsed.offsetSize, parsed.lengthSize, parsed.baseSize, items)
    val units = view.codestreamUnits(coded.codec, coded.items, counted, idat)
    items.visits shouldBe items.size
    units.map { it.start } shouldBe view.codestreamUnits(coded.codec, coded.items, parsed, idat).map { it.start }
    units.size shouldBe 2
    val tiles = coded.items.map { it.first }
    val extents = tiles.map { view.firstExtent(counted, idat, it) }
    items.visits shouldBe items.size
    extents shouldBe tiles.map { view.firstExtent(parsed, idat, it) }
    extents.filterNotNull().size shouldBe MANY_TILES
    val coding = pictureYcbcrCoding(file)
    coding?.bitDepth shouldBe 8
    coding?.chromaFormat shouldBe ChromaFormat.YUV444
    coding?.identityNot444 shouldBe false
  }

  @Test
  fun `a tile listed twice in the iloc is read from its first listing`() {
    val subsampled = SpsShape(chromaFormat = 1).nal()
    pictureYcbcrCoding(manyTiles(4, repeatedInBand = subsampled))?.chromaFormat shouldBe ChromaFormat.YUV444
    val refused = pictureYcbcrCoding(manyTiles(4, firstInBand = subsampled))
    refused?.chromaFormat.shouldBeNull()
    refused?.identityNot444 shouldBe true
  }

  // A HEIC under an identity nclx whose grid, item 1, is derived from count tiles, items 2 onwards, sharing one 4:4:4
  // hvcC. Each tile's iloc entry points at the same slice in mdat, the first tile's behind firstInBand when that is
  // set. With repeatedInBand the first tile is listed again after the rest, pointing at a slice behind that.
  private fun manyTiles(
    count: Int,
    firstInBand: ByteArray? = null,
    repeatedInBand: ByteArray? = null,
  ): ByteArray {
    val tiles = 2..<2 + count
    val sliceData = slice(null)
    val firstData = slice(firstInBand)
    val repeatedData = slice(repeatedInBand)
    val hvcc = hvccOf(VPS_NAL, SpsShape(chromaFormat = 3).nal(), PPS_NAL).also { it[HVCC_CHROMA_AT] = 0xFF.toByte() }
    val properties = box("ipco", hvcc, nclxBox(Cicp(1, 13, 0, true)))
    val iprp = box("iprp", properties, ipma(listOf(1 to listOf(2)) + tiles.map { it to listOf(1) }))
    val dimg =
      box(
        "dimg",
        bytes {
          u16(1).u16(count)
          tiles.forEach { u16(it) }
        },
      )
    val meta = { mdat: Long ->
      val first = mdat + sliceData.size
      val repeated = first + firstData.size
      val entries =
        tiles.map { it to if (it == 2) first to firstData.size else mdat to sliceData.size } +
          listOfNotNull(repeatedInBand?.let { 2 to (repeated to repeatedData.size) })
      val iloc =
        bytes {
          u8(0x44).u8(0).u16(entries.size)
          for ((item, extent) in entries) {
            u16(item).u16(0).u16(0).u16(1)
            u32(extent.first).u32(extent.second.toLong())
          }
        }
      box(
        "meta",
        bytes { u32(0) },
        fullBox("hdlr", 0, 0, ByteArray(21)),
        fullBox("pitm", 0, 0, bytes { u16(1) }),
        fullBox("iloc", 1, 0, iloc),
        iinf(1 to "grid", 2 to "hvc1"),
        fullBox("iref", 0, 0, dimg),
        iprp,
      )
    }
    val fileType = ftyp("heic", "mif1")
    val mdatAt = fileType.size + meta(0).size + 8L
    return fileType + meta(mdatAt) + box("mdat", sliceData, firstData, repeatedData)
  }

  // A slice behind a video, inBand sequence and picture parameter set when inBand is set, each behind a four byte
  // length.
  private fun slice(inBand: ByteArray?): ByteArray {
    val units = (if (inBand != null) listOf(VPS_NAL, inBand, PPS_NAL) else emptyList()) + TestHeic.SLICE
    return bytes { for (unit in units) u32(unit.size.toLong()).raw(unit) }
  }

  // The list it wraps, counting every entry read from it.
  private class Counted<T>(
    private val inner: List<T>,
  ) : AbstractList<T>() {
    var visits = 0

    override val size: Int get() = inner.size

    override fun get(index: Int): T {
      visits++
      return inner[index]
    }
  }

  private companion object {
    // Enough tiles that looking each one up by walking every iloc entry reads millions of entries.
    const val MANY_TILES = 2_000

    // Where an hvcC box holds its chroma layout, 16 bytes into its payload.
    const val HVCC_CHROMA_AT = 24
  }
}
