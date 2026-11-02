package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.UNSPECIFIED_MATRIX_DECODES_AS
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Rewrites the codestream of every test HEIC to state its `nclx`, then reads the result back through core's own
 * reader, walks its boxes and compares its item data with the original's.
 */
class HeicCodestreamRewriteTest {
  private val pictures = ColourPictures.HEICS_WITH_SIGNAL_VARIANTS

  @Test
  fun everyHeicWithAnNclxStatesItInItsCodestream() {
    for (picture in pictures) {
      val original = picture.bytes()
      val rewritten = heifWithExplicitCodestreamColour(original).handed
      val nclx = picture.nclx
      if (nclx == null) {
        val own = CodestreamSignal(2, 2, UNSPECIFIED_MATRIX_DECODES_AS, picture.vui.fullRange)
        assertEquals(own, pictureYcbcrCoding(rewritten)?.codestream, "$picture states its own signal")
        continue
      }
      val coding = pictureYcbcrCoding(rewritten)
      assertEquals(nclx.signal(), coding?.codestream, "$picture codestream signal")
      assertEquals(listOf(nclx.matrix, nclx.fullRange), listOf(coding?.matrix, coding?.fullRange), "$picture nclx")
      assertEquals(mapOf(1 to picture.codestream().toList()), itemData(rewritten), "$picture item data")
    }
  }

  @Test
  fun rewritingKeepsEveryOtherParameterSetField() {
    // Each picture's parameter sets differ from the x265 FullRangeBt709 ones only in their video signal type, so
    // stating that signal over any of them must give those units back exactly.
    val reference = HevcVui.FullRangeBt709.configuration.hex()
    for (picture in pictures.filter { it.nclx != null }) {
      val first = heifWithExplicitCodestreamColour(picture.bytes()).handed
      val retagged =
        HeifStills.still(
          brand = "heic",
          itemType = "hvc1",
          configuration = HeifStills.box("hvcC", hvccPayload(first)),
          codestream = picture.codestream(),
          size = HeicPicture.HEIC_SIZE,
          nclx = CicpTag(1, 13, 1, true),
        )
      assertContentEquals(reference, hvccPayload(heifWithExplicitCodestreamColour(retagged).handed), "$picture")
    }
  }

  @Test
  fun aGridRewritesTheHvcCItsTilesShare() {
    val picture = ColourPictures.HEIC_NCLX_BT709_LIMITED
    val original = grid(picture)
    val rewritten = heifWithExplicitCodestreamColour(original).handed
    assertNotSame(original, rewritten)
    assertEquals(picture.nclx?.signal(), pictureYcbcrCoding(rewritten)?.codestream)
    val tiles = (2..5).associateWith { picture.codestream().toList() }
    assertEquals(mapOf(1 to GRID.toList()) + tiles, itemData(rewritten))
    assertEquals(itemData(original), itemData(rewritten))
  }

  @Test
  fun aSecondRewriteChangesNothing() {
    for (picture in pictures.filter { it.nclx != null }) {
      val once = heifWithExplicitCodestreamColour(picture.bytes()).handed
      assertSame(once, heifWithExplicitCodestreamColour(once).handed, "$picture")
    }
  }

  // A 2 by 2 grid of the picture's codestream, the grid item holding the nclx and its descriptor sitting in idat.
  private fun grid(picture: HeicPicture): ByteArray {
    val ftyp = HeifStills.box("ftyp", "heic".encodeToByteArray() + int32(0) + "mif1heic".encodeToByteArray())
    val codestream = picture.codestream()
    val nclx = picture.nclx!!
    val properties =
      listOf(
        HeifStills.box("hvcC", picture.configuration()),
        fullBox("ispe", int32(64) + int32(64)),
        fullBox("ispe", int32(128) + int32(128)),
        HeifStills.box("colr", "nclx".encodeToByteArray() + nclxFields(nclx)),
      )
    val ipma =
      fullBox(
        "ipma",
        int32(5) + uInt16(1) + byteArrayOf(2, 3, 4) +
          (2..5).fold(ByteArray(0)) { bytes, item -> bytes + uInt16(item) + byteArrayOf(2, 0x81.toByte(), 2) },
      )
    val iinf =
      fullBox(
        "iinf",
        uInt16(5) +
          (1..5).fold(ByteArray(0)) { bytes, item ->
            val type = if (item == 1) "grid" else "hvc1"
            bytes + fullBox("infe", uInt16(item) + uInt16(0) + type.encodeToByteArray() + byteArrayOf(0), version = 2)
          },
      )
    val tiles = (2..5).map { uInt16(it) }.reduce(ByteArray::plus)
    val iref = fullBox("iref", HeifStills.box("dimg", uInt16(1) + uInt16(4) + tiles))

    fun meta(mdatPayload: Int): ByteArray {
      val locations =
        uInt16(1) + uInt16(1) + uInt16(0) + uInt16(1) + int32(0) + int32(GRID.size) +
          (2..5).fold(ByteArray(0)) { bytes, item ->
            val offset = mdatPayload + (item - 2) * codestream.size
            bytes + uInt16(item) + uInt16(0) + uInt16(0) + uInt16(1) + int32(offset) + int32(codestream.size)
          }
      val iloc = fullBox("iloc", byteArrayOf(0x44, 0) + uInt16(5) + locations, version = 1)
      val hdlr = fullBox("hdlr", ByteArray(4) + "pict".encodeToByteArray() + ByteArray(13))
      val iprp = HeifStills.box("iprp", HeifStills.box("ipco", properties.reduce(ByteArray::plus)) + ipma)
      val idat = HeifStills.box("idat", GRID)
      return fullBox("meta", hdlr + fullBox("pitm", uInt16(1)) + iloc + iinf + iref + iprp + idat)
    }
    val mdatPayload = ftyp.size + meta(0).size + 8
    return ftyp + meta(mdatPayload) + HeifStills.box("mdat", List(4) { codestream }.reduce(ByteArray::plus))
  }

  // Every item's data, read through an iloc of version 0 or 1 with four byte offsets and lengths and one extent each.
  private fun itemData(file: ByteArray): Map<Int, List<Byte>> {
    val meta = heifBoxes(file).named("meta")
    val children = file.childrenOf(meta, fullBox = true)
    val iloc = children.named("iloc")
    val version = file.uInt8At(iloc.start)
    assertEquals(listOf(0x44, 0), listOf(file.uInt8At(iloc.start + 4), file.uInt8At(iloc.start + 5)))
    var at = iloc.start + 8
    return List(file.uInt16At(iloc.start + 6)) {
      val item = file.uInt16At(at)
      val method = if (version == 0) 0 else file.uInt16At(at + 2) and 0xF
      at += if (version == 0) 4 else 6
      assertEquals(1, file.uInt16At(at), "one extent")
      val origin = if (method == 1) children.named("idat").start else 0
      val offset = origin + file.int32At(at + 2)
      at += 10
      item to file.copyOfRange(offset, offset + file.int32At(at - 4)).toList()
    }.toMap()
  }

  // The hvcC payload of the file's only hvcC, after checking every box from the top down to it adds up.
  private fun hvccPayload(file: ByteArray): ByteArray {
    val meta = heifBoxes(file).named("meta")
    val iprp = file.childrenOf(meta, fullBox = true).named("iprp")
    val ipco = file.childrenOf(iprp).named("ipco")
    val hvcc = file.childrenOf(ipco).named("hvcC")
    return file.copyOfRange(hvcc.start, hvcc.end)
  }

  private fun CicpTag.signal(): CodestreamSignal = CodestreamSignal(primaries, transfer, matrix, fullRange)

  private fun fullBox(
    type: String,
    payload: ByteArray,
    version: Int = 0,
  ): ByteArray = HeifStills.box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

  private fun nclxFields(cicp: CicpTag): ByteArray =
    uInt16(cicp.primaries) + uInt16(cicp.transfer) + uInt16(cicp.matrix) +
      byteArrayOf(if (cicp.fullRange) 0x80.toByte() else 0)

  private fun int32(value: Int): ByteArray = uInt16(value ushr 16) + uInt16(value)

  private fun uInt16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

  private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private companion object {
    val GRID = byteArrayOf(0, 0, 1, 1, 0, 0x80.toByte(), 0, 0x80.toByte())
  }
}
